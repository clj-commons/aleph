(ns aleph.http.server
  (:require
    [aleph.http.core :as http]
    [aleph.netty :as netty]
    [aleph.flow :as flow]
    [byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    [aleph.http.core
     NettyRequest]
    [io.netty.buffer
     ByteBuf Unpooled]
    [io.netty.channel
     Channel ChannelFuture
     ChannelFutureListener ChannelHandler
     ChannelPipeline DefaultFileRegion]
    [io.netty.handler.codec.http
     DefaultFullHttpResponse
     DefaultHttpContent
     DefaultLastHttpContent
     HttpContent HttpHeaders
     HttpRequest HttpResponse
     HttpResponseStatus DefaultHttpHeaders
     HttpServerCodec HttpVersion
     LastHttpContent]
    [io.netty.handler.codec.http.websocketx
     WebSocketServerHandshakerFactory
     WebSocketServerHandshaker
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     CloseWebSocketFrame
     WebSocketFrame]
    [java.io
     Closeable File InputStream RandomAccessFile]
    [java.nio
     ByteBuffer]
    [java.util.concurrent
     Executor
     ExecutorService
     RejectedExecutionException]
    [java.util.concurrent.atomic
     AtomicInteger
     AtomicBoolean]))

;;;

(defn send-response
  [^Channel ch keep-alive? rsp]
  (let [body (get rsp :body)
        ^HttpResponse rsp (http/ring-response->netty-response rsp)]

    (doto (.headers rsp)
      (.set "Server" "Aleph/0.4.0")
      (.set "Connection" (if keep-alive? "Keep-Alive" "Close")))

    (http/send-message ch keep-alive? rsp body)))

;;;

(defn error-response [^Throwable e]
  (log/error e "error in HTTP handler")
  {:status 500
   :headers {"content-type" "text/plain"}
   :body (let [w (java.io.StringWriter.)]
           (binding [*err* w]
             (.printStackTrace e)
             (str w)))})

(defn invalid-value-response [x]
  (error-response
    (IllegalArgumentException.
      (str "cannot treat " (pr-str x) "as HTTP response"))))

(defn handle-request
  [^Channel ch
   ssl?
   handler
   rejected-handler
   executor
   req
   previous-response
   body
   keep-alive?]
  (let [^NettyRequest req' (http/netty-request->ring-request req ssl? ch body)
        rsp (if executor

              ;; handle request on a separate thread
              (try
                (d/future-with executor
                  (handler req'))
                (catch RejectedExecutionException e
                  (if rejected-handler
                    (try
                      (rejected-handler req')
                      (catch Throwable e
                        (error-response e)))
                    {:status 503
                     :headers {"content-type" "text/plain"}
                     :body "503 Service Unavailable"})))

              ;; handle it inline (hope you know what you're doing)
              (try
                (handler req')
                (catch Throwable e
                  (error-response e))))]

    (-> previous-response
      (d/chain'
        netty/wrap-future
        (fn [_]
          (netty/release req)
          (netty/release body)
          (-> rsp
            (d/catch' error-response)
            (d/chain'
              (fn [rsp]
                (when (not (-> req' ^AtomicBoolean (.websocket?) .get))
                  (send-response ch keep-alive?
                    (if (map? rsp)
                      rsp
                      (invalid-value-response rsp))))))))))))

(defn ring-handler
  [ssl? handler rejected-handler executor buffer-capacity]
  (let [request (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^Channel ch req body]
          (reset! previous-response
            (handle-request
              ch
              ssl?
              handler
              rejected-handler
              executor
              req
              @previous-response
              body
              (HttpHeaders/isKeepAlive req))))]
    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s))
         (doseq [b @buffer]
           (netty/release b)))

      :channel-read
      ([_ ctx msg]
         (cond

           (instance? HttpRequest msg)
           (let [req msg]

             (when (HttpHeaders/is100ContinueExpected req)
               (.writeAndFlush ctx
                 (DefaultFullHttpResponse.
                   HttpVersion/HTTP_1_1
                   HttpResponseStatus/CONTINUE)))

             (if (HttpHeaders/isTransferEncodingChunked req)
               (let [s (s/buffered-stream #(alength ^bytes %) buffer-capacity)]
                 (reset! stream s)
                 (handle-request (.channel ctx) req s))
               (reset! request req)))

           (instance? HttpContent msg)
           (let [content (.content ^HttpContent msg)]
             (if (instance? LastHttpContent msg)
               (do

                 (if-let [s @stream]

                   (do
                     (s/put! s (netty/buf->array content))
                     (netty/release content)
                     (s/close! s))

                   (let [bufs (conj @buffer content)
                         bytes (netty/bufs->array bufs)]
                     (doseq [b bufs]
                       (netty/release b))
                     (handle-request (.channel ctx) @request bytes)))

                 (.set buffer-size 0)
                 (reset! stream nil)
                 (reset! buffer [])
                 (reset! request nil))

               (if-let [s @stream]

                 ;; already have a stream going
                 (do
                   (netty/put! (.channel ctx) s (netty/buf->array content))
                   (netty/release content))

                 (do

                   (swap! buffer conj content)

                   (let [size (.addAndGet buffer-size (.readableBytes ^ByteBuf content))]

                     ;; buffer size exceeded, flush it as a stream
                     (when (< buffer-capacity size)
                       (let [bufs @buffer
                             s (doto (s/buffered-stream #(alength ^bytes %) buffer-capacity)
                                 (s/put! (netty/bufs->array bufs)))]

                         (doseq [b bufs]
                           (netty/release b))

                         (reset! buffer [])
                         (reset! stream s)

                         (handle-request (.channel ctx) @request s)))))))))))))

(defn raw-ring-handler
  [ssl? handler rejected-handler executor buffer-capacity]
  (let [stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^Channel ch req body]
          (reset! previous-response
            (handle-request
              ch
              ssl?
              handler
              rejected-handler
              executor
              req
              @previous-response
              body
              (HttpHeaders/isKeepAlive req))))]
    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s)))

      :channel-read
      ([_ ctx msg]
         (cond

           (instance? HttpRequest msg)
           (let [req msg]

             (when (HttpHeaders/is100ContinueExpected req)
               (.writeAndFlush ctx
                 (DefaultFullHttpResponse.
                   HttpVersion/HTTP_1_1
                   HttpResponseStatus/CONTINUE)))

             (let [s (s/buffered-stream #(.readableBytes ^ByteBuf %) buffer-capacity)]
               (reset! stream s)
               (handle-request (.channel ctx) req s)))

           (instance? HttpContent msg)
           (let [content (.content ^HttpContent msg)]
             (netty/put! (.channel ctx) @stream content)
             (when (instance? LastHttpContent msg)
               (s/close! @stream))))))))

(defn pipeline-builder
  [handler
   {:keys
    [executor
     rejected-handler
     request-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?
     ssl?]
    :or
    {request-buffer-size 16384
     max-initial-line-length 4098
     max-header-size 8196
     max-chunk-size 8196}}]
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-ring-handler ssl? handler rejected-handler executor request-buffer-size)
                    (ring-handler ssl? handler rejected-handler executor request-buffer-size))]

      (doto pipeline
        (.addLast "http-server"
          (HttpServerCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            false))
        (.addLast "request-handler" ^ChannelHandler handler)))))

;;;

(defn wrap-stream->input-stream
  [f
   {:keys [request-buffer-size]
    :or {request-buffer-size 16384}}]
  (fn [{:keys [body] :as req}]
    (f
      (assoc req :body
        (when body
          (netty/to-input-stream body request-buffer-size))))))

(defn start-server
  [handler
   {:keys [port
           executor
           raw-stream?
           bootstrap-transform
           ssl-context
           shutdown-executor?
           rejected-handler]
    :or {bootstrap-transform identity
         shutdown-executor? true}
    :as options}]
  (let [executor (cond
                   (instance? Executor executor)
                   executor

                   (nil? executor)
                   (flow/utilization-executor 0.9 512)

                   (= :none executor)
                   nil

                   :else
                   (throw
                     (IllegalArgumentException.
                       (str "invalid executor specification: " (pr-str executor)))))]
    (netty/start-server
      (pipeline-builder
        (if raw-stream?
          handler
          (wrap-stream->input-stream handler options))
        (assoc options :executor executor :ssl? (boolean ssl-context)))
      ssl-context
      bootstrap-transform
      (when (and shutdown-executor? (instance? ExecutorService executor))
        #(.shutdown ^ExecutorService executor))
      port)))

;;;

(defn websocket-server-handler [raw-stream? ^Channel ch ^WebSocketServerHandshaker handshaker]
  (let [d (d/deferred)
        out (netty/sink ch false
              #(if (instance? CharSequence %)
                 (TextWebSocketFrame. (bs/to-string %))
                 (BinaryWebSocketFrame. (netty/to-byte-buf %))))
        in (s/stream 16)]

    (s/on-drained in
      #(d/chain (.close handshaker ch (CloseWebSocketFrame.))
         netty/wrap-future
         (fn [_] (.close ch))))

    [(s/splice out in)

     (netty/channel-handler

       :exception-handler
       ([_ ctx ex]
          (log/warn ex "error in websocket handler")
          (s/close s)
          (.close ctx))

       :channel-inactive
       ([_ ctx]
          (s/close! out)
          (s/close! in))

       :channel-read
       ([_ ctx msg]
          (try
            (let [ch (.channel ctx)]
              (when (instance? WebSocketFrame msg)
                (let [^WebSocketFrame msg msg]
                  (cond

                    (instance? TextWebSocketFrame msg)
                    (netty/put! ch in (.text ^TextWebSocketFrame msg))

                    (instance? BinaryWebSocketFrame msg)
                    (let [body (.content ^BinaryWebSocketFrame (netty/acquire msg))]
                      (netty/put! ch in
                        (if raw-stream?
                          body
                          (netty/buf->array body))))

                    (instance? PingWebSocketFrame msg)
                    (.writeAndFlush ch (PongWebSocketFrame. (netty/acquire (.content msg))))

                    (instance? CloseWebSocketFrame msg)
                    (.close handshaker ch (netty/acquire msg))))))
            (finally
              (netty/release msg)))))]))

(defn initialize-websocket-handler
  [^NettyRequest req
   {:keys [raw-stream? headers]
    :or {raw-stream? false}
    :as options}]

  (-> req ^AtomicBoolean (.websocket?) (.set true))

  (let [^Channel ch (.ch req)
        url (str
              (if (identical? :https (:scheme req))
                "wss://"
                "ws://")
              (get-in req [:headers "host"])
              (:uri req))
        req (http/ring-request->full-netty-request req)
        factory (WebSocketServerHandshakerFactory. url nil false)]
    (if-let [handshaker (.newHandshaker factory req)]
      (try
        (let [[s ^ChannelHandler handler] (websocket-server-handler raw-stream? ch handshaker)
              p (.newPromise ch)
              h (DefaultHttpHeaders.)]
          (http/map->headers! h headers)
          (-> (try
                (.handshake handshaker ch req h p)
                (netty/wrap-future p)
                (catch Throwable e
                  (d/error-deferred e)))
            (d/chain'
              (fn [x]
                (if x
                  (let [pipeline (.pipeline ch)]
                    (.remove pipeline "request-handler")
                    (.addLast pipeline "websocket-handler" handler)
                    s)
                  (.await p))))
            (d/catch' Throwable
              (fn [e]
                (http/send-message
                  ch
                  false
                  (http/ring-response->netty-response
                    {:status 400
                     :headers {"content-type" "text/plain"}})
                  "expected websocket request")
                e))))
        (catch Throwable e
          (d/error-deferred e)))
      (do
        (WebSocketServerHandshakerFactory/sendUnsupportedVersionResponse ch)
        (d/error-deferred (IllegalStateException. "unsupported version"))))))
