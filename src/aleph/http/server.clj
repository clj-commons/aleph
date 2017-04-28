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
    [java.util
     EnumSet
     TimeZone
     Date
     Locale]
    [java.text
     DateFormat
     SimpleDateFormat]
    [io.aleph.dirigiste
     Stats$Metric]
    [aleph.http.core
     NettyRequest]
    [io.netty.buffer
     ByteBuf Unpooled]
    [io.netty.channel
     Channel ChannelFuture ChannelHandlerContext
     ChannelFutureListener ChannelHandler
     ChannelPipeline DefaultFileRegion]
    [io.netty.handler.codec.http
     DefaultFullHttpResponse
     DefaultHttpContent
     DefaultLastHttpContent
     HttpContent HttpHeaders
     HttpContentCompressor
     HttpRequest HttpResponse
     HttpResponseStatus DefaultHttpHeaders
     HttpServerCodec HttpVersion HttpMethod
     LastHttpContent]
    [io.netty.handler.codec.http.websocketx
     WebSocketServerHandshakerFactory
     WebSocketServerHandshaker
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     CloseWebSocketFrame
     WebSocketFrame
     WebSocketFrameAggregator]
    [java.io
     Closeable File InputStream RandomAccessFile IOException]
    [java.nio
     ByteBuffer]
    [java.net
     InetSocketAddress]
    [io.netty.util.concurrent
     FastThreadLocal]
    [java.util.concurrent
     TimeUnit
     Executor
     ExecutorService
     RejectedExecutionException]
    [java.util.concurrent.atomic
     AtomicReference
     AtomicInteger
     AtomicBoolean]))

(set! *unchecked-math* true)

;;;

(def ^FastThreadLocal date-format (FastThreadLocal.))
(def ^FastThreadLocal date-value (FastThreadLocal.))

(defn rfc-1123-date-string []
  (let [^DateFormat format
        (or
          (.get date-format)
          (let [format (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss z" Locale/ENGLISH)]
            (.setTimeZone format (TimeZone/getTimeZone "GMT"))
            (.set date-format format)
            format))]
    (.format format (Date.))))

(defn ^CharSequence date-header-value [^ChannelHandlerContext ctx]
  (if-let [^AtomicReference ref (.get date-value)]
    (.get ref)
    (let [ref (AtomicReference. (HttpHeaders/newEntity (rfc-1123-date-string)))]
      (.set date-value ref)
      (.scheduleAtFixedRate (.executor ctx)
        #(.set ref (HttpHeaders/newEntity (rfc-1123-date-string)))
        1000
        1000
        TimeUnit/MILLISECONDS)
      (.get ref))))

(defn error-response [^Throwable e]
  (log/error e "error in HTTP handler")
  {:status 500
   :headers {"content-type" "text/plain"}
   :body (let [w (java.io.StringWriter.)]
           (.printStackTrace e (java.io.PrintWriter. w))
           (str w))})

(let [[server-name connection-name date-name]
      (map #(HttpHeaders/newEntity %) ["Server" "Connection" "Date"])

      [server-value keep-alive-value close-value]
      (map #(HttpHeaders/newEntity %) ["Aleph/0.4.4" "Keep-Alive" "Close"])]
  (defn send-response
    [^ChannelHandlerContext ctx keep-alive? ssl? rsp]
    (let [[^HttpResponse rsp body]
          (try
            [(http/ring-response->netty-response rsp)
             (get rsp :body)]
            (catch Throwable e
              (let [rsp (error-response e)]
                [(http/ring-response->netty-response rsp)
                 (get rsp :body)])))]

      (netty/safe-execute ctx

        (doto (.headers rsp)
          (.set ^CharSequence server-name server-value)
          (.set ^CharSequence connection-name (if keep-alive? keep-alive-value close-value))
          (.set ^CharSequence date-name (date-header-value ctx)))

        (http/send-message ctx keep-alive? ssl? rsp body)))))

;;;

(defn invalid-value-response [req x]
  (error-response
    (IllegalArgumentException.
      (str "cannot treat "
        (pr-str x)
        " as HTTP response for request to '"
        (:uri req)
        "'"))))

(defn handle-request
  [^ChannelHandlerContext ctx
   ssl?
   handler
   rejected-handler
   executor
   ^HttpRequest req
   previous-response
   body
   keep-alive?]
  (let [^NettyRequest req' (http/netty-request->ring-request req ssl? (.channel ctx) body)
        head? (identical? HttpMethod/HEAD (.method req))
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
                  (send-response ctx keep-alive? ssl?
                    (cond

                      (map? rsp)
                      (if head?
                        (assoc rsp :body :aleph/omitted)
                        rsp)

                      (nil? rsp)
                      {:status 204}

                      :else
                      (invalid-value-response req rsp))))))))))))

(defn exception-handler [ctx ex]
  (when-not (instance? IOException ex)
    (log/warn ex "error in HTTP server")))

(defn invalid-request? [^HttpRequest req]
  (-> req .decoderResult .isFailure))

(defn reject-invalid-request [ctx ^HttpRequest req]
  (d/chain
    (netty/write-and-flush ctx
      (DefaultFullHttpResponse.
        HttpVersion/HTTP_1_1
        HttpResponseStatus/REQUEST_URI_TOO_LONG
        (-> req .decoderResult .cause .getMessage netty/to-byte-buf)))
    netty/wrap-future
    (fn [_] (netty/close ctx))))

(defn ring-handler
  [ssl? handler rejected-handler executor buffer-capacity]
  (let [buffer-capacity (long buffer-capacity)
        request (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^ChannelHandlerContext ctx req body]
          (reset! previous-response
            (handle-request
              ctx
              ssl?
              handler
              rejected-handler
              executor
              req
              @previous-response
              (when body (bs/to-input-stream body))
              (HttpHeaders/isKeepAlive req))))

        process-request
        (fn [ctx req]
          (when (HttpHeaders/is100ContinueExpected req)
            (netty/write-and-flush ctx
              (DefaultFullHttpResponse.
                HttpVersion/HTTP_1_1
                HttpResponseStatus/CONTINUE)))

          (if (HttpHeaders/isTransferEncodingChunked req)
            (let [s (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)]
              (reset! stream s)
              (handle-request ctx req s))
            (reset! request req)))

        process-last-content
        (fn [ctx ^HttpContent msg]
          (let [content (.content msg)]
            (if-let [s @stream]

              (do
                (s/put! s (netty/buf->array content))
                (netty/release content)
                (s/close! s))

              (if (and (zero? (.get buffer-size))
                    (zero? (.readableBytes content)))

                ;; there was never any body
                (do
                  (netty/release content)
                  (handle-request ctx @request nil))

                (let [bufs (conj @buffer content)
                      bytes (netty/bufs->array bufs)]
                  (doseq [b bufs]
                    (netty/release b))
                  (handle-request ctx @request bytes))))

            (.set buffer-size 0)
            (reset! stream nil)
            (reset! buffer [])
            (reset! request nil)))

        process-content
        (fn [ctx ^HttpContent msg]
          (let [content (.content msg)]
            (if-let [s @stream]

              ;; already have a stream going
              (do
                (netty/put! (netty/channel ctx) s (netty/buf->array content))
                (netty/release content))

              (let [len (.readableBytes ^ByteBuf content)]

                (when-not (zero? len)
                  (swap! buffer conj content))

                (let [size (.addAndGet buffer-size len)]

                  ;; buffer size exceeded, flush it as a stream
                  (when (< buffer-capacity size)
                    (let [bufs @buffer
                          s (doto (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)
                              (s/put! (netty/bufs->array bufs)))]

                      (doseq [b bufs]
                        (netty/release b))

                      (reset! buffer [])
                      (reset! stream s)

                      (handle-request ctx @request s))))))))]

    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
       (exception-handler ctx ex))

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
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (process-request ctx msg))

          (instance? HttpContent msg)
          (if (instance? LastHttpContent msg)
            (process-last-content ctx msg)
            (process-content ctx msg)))))))

(defn raw-ring-handler
  [ssl? handler rejected-handler executor buffer-capacity]
  (let [buffer-capacity (long buffer-capacity)
        stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^ChannelHandlerContext ctx req body]
          (reset! previous-response
            (handle-request
              ctx
              ssl?
              handler
              rejected-handler
              executor
              req
              @previous-response
              body
              (HttpHeaders/isKeepAlive req))))]
    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
       (exception-handler ctx ex))

      :channel-inactive
      ([_ ctx]
        (when-let [s @stream]
          (s/close! s)))

      :channel-read
      ([_ ctx msg]
       (cond

          (instance? HttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (let [req msg]

              (when (HttpHeaders/is100ContinueExpected req)
                (.writeAndFlush ctx
                  (DefaultFullHttpResponse.
                    HttpVersion/HTTP_1_1
                    HttpResponseStatus/CONTINUE)))

              (let [s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)]
                (reset! stream s)
                (handle-request ctx req s))))

          (instance? HttpContent msg)
          (let [content (.content ^HttpContent msg)]
            (netty/put! (.channel ctx) @stream content)
            (when (instance? LastHttpContent msg)
              (s/close! @stream))))))))

(defn pipeline-builder
  [handler
   pipeline-transform
   {:keys
    [executor
     rejected-handler
     request-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?
     ssl?
     compression?]
    :or
    {request-buffer-size 16384
     max-initial-line-length 8192
     max-header-size 8192
     max-chunk-size 16384
     compression? false}}]
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
        (.addLast "request-handler" ^ChannelHandler handler)
        (#(when compression?
            (.addAfter ^ChannelPipeline % "http-server" "deflater" (HttpContentCompressor.))))
        pipeline-transform))))

;;;

(defn start-server
  [handler
   {:keys [port
           socket-address
           executor
           raw-stream?
           bootstrap-transform
           pipeline-transform
           ssl-context
           shutdown-executor?
           rejected-handler
           epoll?
           compression?]
    :or {bootstrap-transform identity
         pipeline-transform identity
         shutdown-executor? true
         epoll? false
         compression? false}
    :as options}]
  (let [executor (cond
                   (instance? Executor executor)
                   executor

                   (nil? executor)
                   (flow/utilization-executor 0.9 512
                     {:metrics (EnumSet/of Stats$Metric/UTILIZATION)
                      ;;:onto? false
                      })

                   (= :none executor)
                   nil

                   :else
                   (throw
                     (IllegalArgumentException.
                       (str "invalid executor specification: " (pr-str executor)))))]
    (netty/start-server
      (pipeline-builder
        handler
        pipeline-transform
        (assoc options :executor executor :ssl? (boolean ssl-context)))
      ssl-context
      bootstrap-transform
      (when (and shutdown-executor? (instance? ExecutorService executor))
        #(.shutdown ^ExecutorService executor))
      (if socket-address socket-address (InetSocketAddress. port))
      epoll?)))

;;;

(defn websocket-server-handler
  [raw-stream?
   ^Channel ch
   ^WebSocketServerHandshaker handshaker]
  (let [d (d/deferred)
        out (netty/sink ch false
              #(if (instance? CharSequence %)
                 (TextWebSocketFrame. (bs/to-string %))
                 (BinaryWebSocketFrame. (netty/to-byte-buf ch %))))
        in (netty/buffered-source ch (constantly 1) 16)]

    (s/on-drained in
      #(d/chain' (.close handshaker ch (CloseWebSocketFrame.))
         netty/wrap-future
         (fn [_] (.close ch))))

    [(doto
       (s/splice out in)
       (reset-meta! {:aleph/channel ch}))

     (netty/channel-handler

       :exception-caught
       ([_ ctx ex]

         (when-not (instance? IOException ex)
           (log/warn ex "error in websocket handler"))
         (s/close! out)
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
                   (let [body (.content ^BinaryWebSocketFrame msg)]
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
   {:keys [raw-stream? headers max-frame-payload max-frame-size allow-extensions?]
    :or {raw-stream? false, max-frame-payload 65536, max-frame-size 1048576, allow-extensions? false}
    :as options}]

  (-> req ^AtomicBoolean (.websocket?) (.set true))

  (let [^Channel ch (.ch req)
        ssl? (identical? :https (:scheme req))
        url (str
              (if ssl? "wss://" "ws://")
              (get-in req [:headers "host"])
              (:uri req))
        req (http/ring-request->full-netty-request req)
        factory (WebSocketServerHandshakerFactory. url nil allow-extensions? max-frame-payload)]
    (if-let [handshaker (.newHandshaker factory req)]
      (try
        (let [[s ^ChannelHandler handler] (websocket-server-handler raw-stream? ch handshaker)
              p (.newPromise ch)
              h (DefaultHttpHeaders.)]
          (http/map->headers! h headers)
          (-> (try
                (netty/wrap-future (.handshake handshaker ch ^HttpRequest req h p))
                (catch Throwable e
                  (d/error-deferred e)))
            (d/chain'
              (fn [_]
                (let [pipeline (.pipeline ch)]
                  (.remove pipeline "request-handler")
                  (.addLast pipeline "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
                  (.addLast pipeline "websocket-handler" handler)
                  s)))
            (d/catch'
              (fn [e]
                (http/send-message
                  ch
                  false
                  ssl?
                  (http/ring-response->netty-response
                    {:status 400
                     :headers {"content-type" "text/plain"}})
                  "expected websocket request")
                (d/error-deferred e)))))
        (catch Throwable e
          (d/error-deferred e)))
      (do
        (WebSocketServerHandshakerFactory/sendUnsupportedVersionResponse ch)
        (d/error-deferred (IllegalStateException. "unsupported version"))))))
