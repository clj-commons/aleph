(ns aleph.http.client
  (:require
    [clojure.tools.logging :as log]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http.core :as http]
    [aleph.http.multipart :as multipart]
    [aleph.http.client-middleware :as middleware]
    [aleph.netty :as netty])
  (:import
    [java.io
     IOException]
    [java.net
     URI
     URL
     InetSocketAddress
     IDN]
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
     WebSocketFrameAggregator
     WebSocketVersion]
    [java.util.concurrent.atomic
     AtomicInteger]))

(set! *unchecked-math* true)

;;;

(let [no-url (fn [req]
               (URI.
                 (name (or (:scheme req) :http))
                 nil
                 (some-> (or (:host req) (:server-name req)) IDN/toASCII)
                 (or (:port req) (:server-port req) -1)
                 nil
                 nil
                 nil))]

  (defn ^java.net.URI req->domain [req]
    (if-let [url (:url req)]
      (let [^URL uri (URL. url)]
        (URI.
          (.getProtocol uri)
          nil
          (IDN/toASCII (.getHost uri))
          (.getPort uri)
          nil
          nil
          nil))
      (no-url req))))

(defn raw-client-handler
  [response-stream buffer-capacity]
  (let [stream (atom nil)
        previous-response (atom nil)
        complete (atom nil)

        handle-response
        (fn [response complete body]
          (s/put! response-stream
            (http/netty-response->ring-response
              response
              complete
              body)))]

    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
       (when-not (instance? IOException ex)
         (log/warn ex "error in HTTP client")))

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

            (let [s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)
                  c (d/deferred)]
              (reset! stream s)
              (reset! complete c)
              (s/on-closed s #(d/success! c true))
              (handle-response rsp c s)))

          (instance? HttpContent msg)
          (let [content (.content ^HttpContent msg)]
            (netty/put! (.channel ctx) @stream content)
            (when (instance? LastHttpContent msg)
              (d/success! @complete false)
              (s/close! @stream))))))))

(defn client-handler
  [response-stream ^long buffer-capacity]
  (let [response (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        stream (atom nil)
        complete (atom nil)
        handle-response (fn [rsp complete body]
                          (s/put! response-stream
                            (http/netty-response->ring-response
                              rsp
                              complete
                              body)))]

    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
        (when-not (instance? IOException ex)
          (log/warn ex "error in HTTP client")))

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
              (let [s (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)
                    c (d/deferred)]
                (reset! stream s)
                (reset! complete c)
                (s/on-closed s #(d/success! c true))
                (handle-response rsp c s))
              (reset! response rsp)))

          (instance? HttpContent msg)
          (let [content (.content ^HttpContent msg)]
            (if (instance? LastHttpContent msg)
              (do

                (if-let [s @stream]

                  (do
                    (s/put! s (netty/buf->array content))
                    (netty/release content)
                    (d/success! @complete false)
                    (s/close! s))

                  (let [bufs (conj @buffer content)
                        bytes (netty/bufs->array bufs)]
                    (doseq [b bufs]
                      (netty/release b))
                    (handle-response @response (d/success-deferred false) bytes)))

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
                            c (d/deferred)
                            s (doto (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) 16384)
                                (s/put! (netty/bufs->array bufs)))]

                        (doseq [b bufs]
                          (netty/release b))

                        (reset! buffer [])
                        (reset! stream s)
                        (reset! complete c)

                        (s/on-closed s #(d/success! c true))

                        (handle-response @response c s)))))))))))))

(defn pipeline-builder
  [response-stream
   {:keys
    [pipeline-transform
     response-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?]
    :or
    {pipeline-transform identity
     response-buffer-size 65536
     max-initial-line-length 65536
     max-header-size 65536
     max-chunk-size 65536}}]
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
        (.addLast "handler" ^ChannelHandler handler)
        pipeline-transform))))

(defn close-connection [f]
  (f
    {:method :get
     :url "http://example.com"
     ::close true}))

(defn http-connection
  [^InetSocketAddress remote-address
   ssl?
   {:keys [local-address
           raw-stream?
           bootstrap-transform
           pipeline-transform
           keep-alive?
           insecure?
           ssl-context
           response-buffer-size
           on-closed
           response-executor
           epoll?]
    :or {bootstrap-transform identity
         keep-alive? true
         response-buffer-size 65536
         epoll? false}
    :as options}]
  (let [responses (s/stream 1024 nil response-executor)
        requests (s/stream 1024 nil nil)
        host (.getHostName remote-address)
        port (.getPort remote-address)
        explicit-port? (and (pos? port) (not= port (if ssl? 443 80)))
        c (netty/create-client
            (pipeline-builder
              responses
              (if pipeline-transform
                (assoc options :pipeline-transform pipeline-transform)
                options))
            (when ssl?
              (or ssl-context
                (if insecure?
                  (netty/insecure-ssl-client-context)
                  (netty/ssl-client-context))))
            bootstrap-transform
            remote-address
            local-address
            epoll?)]
    (d/chain' c
      (fn [^Channel ch]

        (s/consume
          (fn [req]
            (try
              (let [^HttpRequest req' (http/ring-request->netty-request req)]
                (when-not (.get (.headers req') "Host")
                  (HttpHeaders/setHost req' (str host (when explicit-port? (str ":" port)))))
                (when-not (.get (.headers req') "Connection")
                  (HttpHeaders/setKeepAlive req' keep-alive?))

                (let [body (if-let [parts (get req :multipart)]
                             (let [boundary (multipart/boundary)
                                   content-type (str "multipart/form-data; boundary=" boundary)]
                               (HttpHeaders/setHeader req' "Content-Type" content-type)
                               (multipart/encode-body boundary parts))
                             (get req :body))]
                  (netty/safe-execute ch
                                      (http/send-message ch true ssl? req' body))))

              ;; this will usually happen because of a malformed request
              (catch Throwable e
                (s/put! responses (d/error-deferred e)))))
          requests)

        (s/on-closed responses
          (fn []
            (when on-closed (on-closed))
            (s/close! requests)))

        (let [t0 (System/nanoTime)]
          (fn [req]
            (if (contains? req ::close)
              (netty/wrap-future (netty/close ch))
              (let [raw-stream? (get req :raw-stream? raw-stream?)
                    rsp (locking ch
                          (s/put! requests req)
                          (s/take! responses ::closed))]
                (d/chain' rsp
                  (fn [rsp]
                    (cond
                      (identical? ::closed rsp)
                      (d/error-deferred
                        (ex-info
                          (format "connection was closed after %.3f seconds" (/ (- (System/nanoTime) t0) 1e9))
                          {:request req}))

                      raw-stream?
                      rsp

                      :else
                      (d/chain' rsp
                        (fn [rsp]
                          (let [body (:body rsp)]

                            ;; handle connection life-cycle
                            (when-not keep-alive?
                              (if (s/stream? body)
                                (s/on-closed body #(netty/close ch))
                                (netty/close ch)))

                            (assoc rsp
                              :body
                              (bs/to-input-stream body
                                {:buffer-size response-buffer-size}))))))))))))))))

;;;

(defn websocket-frame-size [^WebSocketFrame frame]
  (-> frame .content .readableBytes))

(defn ^WebSocketClientHandshaker websocket-handshaker [uri sub-protocols extensions? headers max-frame-payload]
  (WebSocketClientHandshakerFactory/newHandshaker
    uri
    WebSocketVersion/V13
    sub-protocols
    extensions?
    (doto (DefaultHttpHeaders.) (http/map->headers! headers))
    max-frame-payload))

(defn websocket-client-handler [raw-stream? uri sub-protocols extensions? headers max-frame-payload]
  (let [d (d/deferred)
        in (atom nil)
        desc (atom {})
        handshaker (websocket-handshaker uri sub-protocols extensions? headers max-frame-payload)]

    [d

     (netty/channel-handler

       :exception-caught
       ([_ ctx ex]
         (when-not (d/error! d ex)
           (log/warn ex "error in websocket client"))
         (s/close! @in)
         (netty/close ctx))

       :channel-inactive
       ([_ ctx]
         (when (realized? d)
           (s/close! @d)))

       :channel-active
       ([_ ctx]
         (let [ch (.channel ctx)]
           (reset! in (netty/buffered-source ch (constantly 1) 16))
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
                                (BinaryWebSocketFrame. (netty/to-byte-buf ctx %)))
                             (fn [] @desc))]

                   (d/success! d
                     (doto
                       (s/splice out @in)
                       (reset-meta! {:aleph/channel ch})))

                   (s/on-drained @in
                     #(d/chain' (.writeAndFlush ch (CloseWebSocketFrame.))
                        netty/wrap-future
                        (fn [_] (netty/close ctx))))))

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
               (netty/put! ch @in (.text ^TextWebSocketFrame msg))

               (instance? BinaryWebSocketFrame msg)
               (let [frame (.content ^BinaryWebSocketFrame msg)]
                 (netty/put! ch @in
                   (if raw-stream?
                     (netty/acquire frame)
                     (netty/buf->array frame))))

               (instance? PongWebSocketFrame msg)
               nil

               (instance? CloseWebSocketFrame msg)
               (let [frame ^CloseWebSocketFrame msg]
                 (when (realized? d)
                   (swap! desc assoc
                          :websocket-close-code (.statusCode frame)
                          :websocket-close-msg (.reasonText frame)))
                 (netty/close ctx))))
           (finally
             (netty/release msg)))))]))

(defn websocket-connection
  [uri
   {:keys [raw-stream? bootstrap-transform insecure? headers local-address epoll?
           sub-protocols extensions? max-frame-payload max-frame-size]
    :or {bootstrap-transform identity
         keep-alive? true
         raw-stream? false
         epoll? false
         sub-protocols nil
         extensions? false
         max-frame-payload 65536
         max-frame-size 1048576}
    :as options}]
  (let [uri (URI. uri)
        ssl? (= "wss" (.getScheme uri))
        [s handler] (websocket-client-handler raw-stream? uri sub-protocols extensions? headers max-frame-payload)]

    (assert (#{"ws" "wss"} (.getScheme uri)) "scheme must be one of 'ws' or 'wss'")

    (d/chain'
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (doto pipeline
            (.addLast "http-client" (HttpClientCodec.))
            (.addLast "aggregator" (HttpObjectAggregator. 16384))
            (.addLast "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
            (.addLast "handler" ^ChannelHandler handler)))
        (when ssl?
          (if insecure?
            (netty/insecure-ssl-client-context)
            (netty/ssl-client-context)))
        bootstrap-transform
        (InetSocketAddress.
          (.getHost uri)
          (int
            (if (neg? (.getPort uri))
              (if ssl? 443 80)
              (.getPort uri))))
        local-address
        epoll?)
      (fn [_]
        s))))
