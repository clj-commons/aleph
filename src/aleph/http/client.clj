(ns aleph.http.client
  (:require
    [clojure.tools.logging :as log]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http.core :as http]
    [aleph.http.client-middleware :as middleware]
    [aleph.netty :as netty])
  (:import
    [java.net
     URI]
    [io.netty.buffer
     ByteBuf
     Unpooled]
    [io.netty.handler.codec.http
     HttpMessage
     HttpClientCodec
     DefaultHttpHeaders
     HttpHeaders
     HttpRequest
     HttpResponse
     HttpContent
     LastHttpContent
     FullHttpResponse
     DefaultLastHttpContent
     DefaultHttpContent
     DefaultFullHttpResponse
     HttpVersion
     HttpResponseStatus
     HttpObjectAggregator]
    [io.netty.channel
     Channel ChannelFuture
     ChannelFutureListener ChannelHandler
     ChannelPipeline]
    [io.netty.handler.codec.http.websocketx
     CloseWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     WebSocketClientHandshaker
     WebSocketClientHandshakerFactory
     WebSocketFrame
     WebSocketVersion]
    [java.util.concurrent.atomic
     AtomicInteger]))

(set! *unchecked-math* true)

;;;

(defn raw-client-handler
  [response-stream buffer-capacity]
  (let [stream (atom nil)
        previous-response (atom nil)

        handle-response
        (fn [response body]
          (s/put! response-stream
            (http/netty-response->ring-response
              response
              body)))]

    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP client"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s))
         (s/close! response-stream))

      :channel-read
      ([_ ctx msg]
         (cond

           (instance? HttpResponse msg)
           (let [rsp msg]

             (let [s (s/buffered-stream #(.readableBytes ^ByteBuf %) buffer-capacity)]
               (reset! stream s)
               (handle-response rsp s)))

           (instance? HttpContent msg)
           (let [content (.content ^HttpContent msg)]
             (netty/put! (.channel ctx) @stream content)
             (when (instance? LastHttpContent msg)
               (s/close! @stream))))))))

(defn client-handler
  [response-stream ^long buffer-capacity]
  (let [response (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        stream (atom nil)
        handle-response (fn [rsp body]
                          (s/put! response-stream
                            (http/netty-response->ring-response
                              rsp
                              body)))]

    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s))
         (doseq [b @buffer]
           (netty/release b))
         (s/close! response-stream))

      :channel-read
      ([_ ctx msg]
         (cond

           (instance? HttpResponse msg)
           (let [rsp msg]

             (if (HttpHeaders/isTransferEncodingChunked rsp)
               (let [s (s/buffered-stream #(alength ^bytes %) buffer-capacity)]
                 (reset! stream s)
                 (handle-response rsp s))
               (reset! response rsp)))

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
                     (handle-response @response bytes)))

                 (.set buffer-size 0)
                 (reset! stream nil)
                 (reset! buffer [])
                 (reset! response nil))

               (if-let [s @stream]

                 ;; already have a stream going
                 (do
                   (netty/put! (.channel ctx) s (netty/buf->array content))
                   (netty/release content))

                 (let [len (.readableBytes ^ByteBuf content)]

                   (when-not (zero? len)
                     (swap! buffer conj content))

                   (let [size (.addAndGet buffer-size len)]

                     ;; buffer size exceeded, flush it as a stream
                     (when (< buffer-capacity size)
                       (let [bufs @buffer
                             s (doto (s/buffered-stream #(alength ^bytes %) 16384)
                                 (s/put! (netty/bufs->array bufs)))]

                         (doseq [b bufs]
                           (netty/release b))

                         (reset! buffer [])
                         (reset! stream s)

                         (handle-response @response s)))))))))))))

(defn pipeline-builder
  [response-stream
   {:keys
    [response-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?]
    :or
    {response-buffer-size 65536
     max-initial-line-length 4098
     max-header-size 8196
     max-chunk-size 8196}}]
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-client-handler response-stream response-buffer-size)
                    (client-handler response-stream response-buffer-size))]

      (doto pipeline
        (.addLast "http-client"
          (HttpClientCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            false
            false))
        (.addLast "handler" ^ChannelHandler handler)))))

(defn close-connection [f]
  (f
    {:method :get
     :url "http://example.com"
     ::close true}))

(defn http-connection
  [host
   port
   ssl?
   {:keys [raw-stream? bootstrap-transform keep-alive? insecure? response-buffer-size]
    :or {bootstrap-transform identity
         keep-alive? true
         response-buffer-size 65536}
    :as options}]
  (let [responses (s/stream 1024)
        requests (s/stream 1024)
        c (netty/create-client
            (pipeline-builder
              responses
              options)
            (when ssl?
              (if insecure?
                (netty/insecure-ssl-client-context)
                (netty/ssl-client-context)))
            bootstrap-transform
            host
            port)]
    (d/chain c
      (fn [^Channel ch]

        (s/consume
          (fn [req]
            (let [^HttpRequest req' (http/ring-request->netty-request req)]
              (HttpHeaders/setHost req' ^String host)
              (HttpHeaders/setKeepAlive req' keep-alive?)
              (netty/safe-execute ch
                (http/send-message ch true req' (get req :body)))))
          requests)

        (s/on-closed responses #(s/close! requests))

        (fn [req]
          (if (contains? req ::close)
            (netty/wrap-future (.close ch))
            (let [rsp (locking ch
                        (s/put! requests req)
                        (s/take! responses))]
              (if raw-stream?
                rsp
                (d/chain rsp
                  (fn [rsp]
                    (prn keep-alive? rsp)
                    (let [body (:body rsp)]
                      (when-not keep-alive?
                        (if (s/stream? body)
                          (s/on-closed body #(do (prn 'closing!) (.close ch)))
                          (do (prn 'closing!) (.close ch))))
                      (assoc rsp :body
                        (bs/to-input-stream body
                          {:buffer-size response-buffer-size})))))))))))))

;;;

(defn websocket-frame-size [^WebSocketFrame frame]
  (-> frame .content .readableBytes))

(defn ^WebSocketClientHandshaker websocket-handshaker [uri headers]
  (WebSocketClientHandshakerFactory/newHandshaker
    uri
    WebSocketVersion/V13
    nil
    false
    (doto (DefaultHttpHeaders.) (http/map->headers! headers))))

(defn websocket-client-handler [raw-stream? uri headers]
  (let [d (d/deferred)
        in (s/stream 16)
        handshaker (websocket-handshaker uri headers)]

    [d

     (netty/channel-handler

       :exception-handler
       ([_ ctx ex]
          (when-not (d/error! d ex)
            (log/warn ex "error in websocket client"))
          (s/close s)
          (.close ctx))

       :channel-inactive
       ([_ ctx]
          (when (realized? d)
            (s/close! @d)))

       :channel-active
       ([_ ctx]
          (let [ch (.channel ctx)]
            (.handshake handshaker ch)))

       :channel-read
       ([_ ctx msg]
          (try
            (let [ch (.channel ctx)]
              (cond

                (not (.isHandshakeComplete handshaker))
                (do
                  (.finishHandshake handshaker ch msg)
                  (let [out (netty/sink ch false
                              #(if (instance? CharSequence %)
                                 (TextWebSocketFrame. (bs/to-string %))
                                 (BinaryWebSocketFrame. (netty/to-byte-buf ctx %))))]

                    (d/success! d (s/splice out in))

                    (s/on-drained in
                      #(d/chain (.writeAndFlush ch (CloseWebSocketFrame.))
                         netty/wrap-future
                         (fn [_] (.close ctx))))))

                (instance? FullHttpResponse msg)
                (let [rsp ^FullHttpResponse msg]
                  (throw
                    (IllegalStateException.
                      (str "unexpected HTTP response, status: "
                        (.getStatus rsp)
                        ", body: '"
                        (bs/to-string (.content rsp))
                        "'"))))

                (instance? TextWebSocketFrame msg)
                (netty/put! ch in (.text ^TextWebSocketFrame msg))

                (instance? BinaryWebSocketFrame msg)
                (let [frame (netty/acquire (.content ^BinaryWebSocketFrame msg))]
                  (netty/put! ch in
                    (if raw-stream?
                      frame
                      (netty/buf->array frame))))

                (instance? PongWebSocketFrame msg)
                nil

                (instance? CloseWebSocketFrame msg)
                (.close ctx)))
            (finally
              (netty/release msg)))))]))

(defn websocket-connection
  [uri
   {:keys [raw-stream? bootstrap-transform insecure? headers]
    :or {bootstrap-transform identity
         keep-alive? true
         raw-stream? false}
    :as options}]
  (let [uri (URI. uri)
        ssl? (= "wss" (.getScheme uri))
        [s handler] (websocket-client-handler raw-stream? uri headers)]

    (assert (#{"ws" "wss"} (.getScheme uri)) "scheme must be one of 'ws' or 'wss'")

    (d/chain
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (doto pipeline
            (.addLast "http-client" (HttpClientCodec.))
            (.addLast "aggregator" (HttpObjectAggregator. 16384))
            (.addLast "handler" ^ChannelHandler handler)))
        (when ssl?
          (if insecure?
            (netty/insecure-ssl-client-context)
            (netty/ssl-client-context)))
        bootstrap-transform
        (.getHost uri)
        (if (neg? (.getPort uri))
          (if ssl? 443 80)
          (.getPort uri)))
      (fn [_]
        s))))
