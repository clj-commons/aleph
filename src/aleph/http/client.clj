(ns aleph.http.client
  (:require
    [clojure.tools.logging :as log]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http.core :as http]
    [aleph.http.multipart :as multipart]
    [aleph.netty :as netty]
    [manifold.time :as time])
  (:import
    [java.io
     IOException]
    [java.net
     URI
     InetSocketAddress
     IDN
     URL]
    [io.netty.buffer
     ByteBuf]
    [io.netty.handler.codec.http
     HttpClientCodec
     DefaultHttpHeaders
     HttpHeaders
     HttpRequest
     HttpResponse
     HttpContent
     HttpUtil
     HttpHeaderNames
     LastHttpContent
     FullHttpResponse
     HttpObjectAggregator]
    [io.netty.channel
     Channel
     ChannelHandler ChannelHandlerContext
     ChannelPipeline]
    [io.netty.handler.stream ChunkedWriteHandler]
    [io.netty.handler.codec.http FullHttpRequest]
    [io.netty.handler.codec.http.websocketx
     CloseWebSocketFrame
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     WebSocketClientHandshaker
     WebSocketClientHandshakerFactory
     WebSocketFrame
     WebSocketFrameAggregator
     WebSocketVersion]
    [io.netty.handler.codec.http.websocketx.extensions.compression
     WebSocketClientCompressionHandler]
    [io.netty.handler.proxy
     ProxyConnectionEvent
     ProxyConnectException
     ProxyHandler
     HttpProxyHandler
     Socks4ProxyHandler
     Socks5ProxyHandler]
    [io.netty.handler.logging
     LoggingHandler
     LogLevel]
    [java.util.concurrent
     Future]
    [java.util.concurrent.atomic
     AtomicInteger]
    [aleph.utils
     ProxyConnectionTimeoutException
     WebSocketHandshakeTimeoutException]))

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
        complete (atom nil)

        handle-response
        (fn [response complete body]
          (s/put! response-stream
            (http/netty-response->ring-response
              response
              complete
              body)))]

    (netty/channel-inbound-handler

      :exception-caught
      ([_ ctx ex]
        (when-not (instance? IOException ex)
          (log/warn ex "error in HTTP client")))

      :channel-inactive
      ([_ ctx]
        (when-let [s @stream]
          (s/close! s))
        (s/close! response-stream)
        (.fireChannelInactive ctx))

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
              (s/close! @stream)))

          :else
          (.fireChannelRead ctx msg))))))

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

    (netty/channel-inbound-handler

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
        (s/close! response-stream)
        (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]

        (cond

          (instance? HttpResponse msg)
          (let [rsp msg]
            (if (HttpUtil/isTransferEncodingChunked rsp)
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

                        (handle-response @response c s))))))))

          :else
          (.fireChannelRead ctx msg))))))

(defn non-tunnel-proxy? [{:keys [tunnel? user http-headers ssl?]
                          :as proxy-options}]
  (and (some? proxy-options)
    (not tunnel?)
    (not ssl?)
    (nil? user)
    (nil? http-headers)))

(defn http-proxy-headers [{:keys [http-headers keep-alive?]
                           :or {http-headers {}
                                keep-alive? true}}]
  (let [headers (DefaultHttpHeaders.)]
    (http/map->headers! headers http-headers)
    (when keep-alive?
      (.set headers "Proxy-Connection" "Keep-Alive"))
    headers))

;; `tunnel?` is set to `false` by default when not using `ssl?`
;; Following `curl` in both cases:
;;
;;  * `curl` uses separate option `--proxytunnel` flag to switch tunneling on
;;  * `curl` uses CONNECT when sending request to HTTPS destination through HTTP proxy
;;
;; Explicitly setting `tunnel?` to false when it's expected to use CONNECT
;; throws `IllegalArgumentException` to reduce the confusion
(defn http-proxy-handler
  [^InetSocketAddress address
   {:keys [user password http-headers tunnel? keep-alive? ssl?]
    :or {keep-alive? true}
    :as options}]
  (let [options' (assoc options :tunnel? (or tunnel? ssl?))]
    (when (and (nil? user) (some? password))
      (throw (IllegalArgumentException.
               "Could not setup http proxy with basic auth: 'user' is missing")))

    (when (and (some? user) (nil? password))
      (throw (IllegalArgumentException.
               "Could not setup http proxy with basic auth: 'password' is missing")))

    (when (and (false? tunnel?)
            (or (some? user)
              (some? http-headers)
              (true? ssl?)))
      (throw (IllegalArgumentException.
               (str "Proxy options given require sending CONNECT request, "
                 "but `tunnel?' option is set to 'false' explicitely. "
                 "Consider setting 'tunnel?' to 'true' or omit it at all"))))

    (if (non-tunnel-proxy? options')
      (netty/channel-outbound-handler
        :connect
        ([_ ctx remote-address local-address promise]
          (.connect ^ChannelHandlerContext ctx address local-address promise)))

      ;; this will send CONNECT request to the proxy server
      (let [headers (http-proxy-headers options')]
        (if (nil? user)
          (HttpProxyHandler. address headers)
          (HttpProxyHandler. address user password headers))))))

(defn proxy-handler [{:keys [host port protocol user password connection-timeout]
                      :or {protocol :http
                           connection-timeout 6e4}
                      :as options}]
  {:pre [(some? host)]}
  (let [port' (int (cond
                     (some? port) port
                     (= :http protocol) 80
                     (= :socks4 protocol) 1080
                     (= :socks5 protocol) 1080))
        proxy-address (InetSocketAddress. ^String host port')
        handler (case protocol
                  :http (http-proxy-handler proxy-address options)
                  :socks4 (if (some? user)
                            (Socks4ProxyHandler. proxy-address user)
                            (Socks4ProxyHandler. proxy-address))
                  :socks5 (if (some? user)
                            (Socks5ProxyHandler. proxy-address user password)
                            (Socks5ProxyHandler. proxy-address))
                  (throw
                    (IllegalArgumentException.
                      (format "Proxy protocol '%s' not supported. Use :http, :socks4 or socks5"
                        protocol))))]
    (when (instance? ProxyHandler handler)
      (.setConnectTimeoutMillis ^ProxyHandler handler connection-timeout))
    handler))

(defn pending-proxy-connection-handler [response-stream]
  (netty/channel-inbound-handler
    :exception-caught
    ([_ ctx cause]
      (if-not (instance? ProxyConnectException cause)
        (.fireExceptionCaught ^ChannelHandlerContext ctx cause)
        (do
          (s/put! response-stream (ProxyConnectionTimeoutException. cause))
          ;; client handler should take care of the rest
          (netty/close ctx))))

    :user-event-triggered
    ([this ctx evt]
      (when (instance? ProxyConnectionEvent evt)
        (.remove (.pipeline ctx) this))
      (.fireUserEventTriggered ^ChannelHandlerContext ctx evt))))

(defn coerce-log-level [level]
  (if (instance? LogLevel level)
    level
    (let [netty-level (case level
                        :trace LogLevel/TRACE
                        :debug LogLevel/DEBUG
                        :info LogLevel/INFO
                        :warn LogLevel/WARN
                        :error LogLevel/ERROR
                        nil)]
      (when (nil? netty-level)
        (throw (IllegalArgumentException.
                (str "unknown log level given: " level))))
      netty-level)))

(defn pipeline-builder
  [response-stream
   {:keys
    [pipeline-transform
     response-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?
     proxy-options
     ssl?
     idle-timeout
     log-activity]
    :or
    {pipeline-transform identity
     response-buffer-size 65536
     max-initial-line-length 65536
     max-header-size 65536
     max-chunk-size 65536
     idle-timeout 0}}]
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-client-handler response-stream response-buffer-size)
                    (client-handler response-stream response-buffer-size))
          logger (when (some? log-activity)
                   (LoggingHandler.
                    "aleph-client"
                    ^LogLevel (coerce-log-level log-activity)))]
      (doto pipeline
        (.addLast "http-client"
          (HttpClientCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            false
            false))
        (.addLast "streamer" ^ChannelHandler (ChunkedWriteHandler.))
        (.addLast "handler" ^ChannelHandler handler)
        (http/attach-idle-handlers idle-timeout))
      (when (some? proxy-options)
        (let [proxy (proxy-handler (assoc proxy-options :ssl? ssl?))]
          (.addFirst pipeline "proxy" ^ChannelHandler proxy)
          ;; well, we need to wait before the proxy responded with
          ;; HTTP/1.1 200 Connection established
          ;; before sending any requests
          (when (instance? ProxyHandler proxy)
            (.addAfter pipeline
              "proxy"
              "pending-proxy-connection"
              ^ChannelHandler
              (pending-proxy-connection-handler response-stream)))))
      (when (some? logger)
        (.addFirst pipeline "activity-logger" logger))
      (pipeline-transform pipeline))))

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
           name-resolver
           keep-alive?
           insecure?
           ssl-context
           response-buffer-size
           on-closed
           response-executor
           epoll?
           proxy-options]
    :or {bootstrap-transform identity
         keep-alive? true
         response-buffer-size 65536
         epoll? false
         name-resolver :default}
    :as options}]
  (let [responses (s/stream 1024 nil response-executor)
        requests (s/stream 1024 nil nil)
        host (.getHostName remote-address)
        port (.getPort remote-address)
        explicit-port? (and (pos? port) (not= port (if ssl? 443 80)))
        c (netty/create-client
            (pipeline-builder responses (assoc options :ssl? ssl?))
            (when ssl?
              (or ssl-context
                (if insecure?
                  (netty/insecure-ssl-client-context)
                  (netty/ssl-client-context))))
            bootstrap-transform
            remote-address
            local-address
            epoll?
            name-resolver)]
    (d/chain' c
      (fn [^Channel ch]

        (s/consume
          (fn [req]

            (try
              (let [proxy-options' (when (some? proxy-options)
                                     (assoc proxy-options :ssl? ssl?))
                    ^HttpRequest req' (http/ring-request->netty-request
                                        (if (non-tunnel-proxy? proxy-options')
                                          (assoc req :uri (:request-url req))
                                          req))]
                (when-not (.get (.headers req') "Host")
                  (.set (.headers req') HttpHeaderNames/HOST (str host (when explicit-port? (str ":" port)))))
                (when-not (.get (.headers req') "Connection")
                  (HttpUtil/setKeepAlive req' keep-alive?))
                (when (and (non-tunnel-proxy? proxy-options')
                        (get proxy-options :keep-alive? true)
                        (not (.get (.headers req') "Proxy-Connection")))
                  (.set (.headers req') "Proxy-Connection" "Keep-Alive"))

                (let [body (:body req)
                      parts (:multipart req)
                      multipart? (some? parts)
                      [req' body] (cond
                                    ;; RFC #7231 4.3.8. TRACE
                                    ;; A client MUST NOT send a message body...
                                    (= :trace (:request-method req))
                                    (do
                                      (when (or (some? body) multipart?)
                                        (log/warn "TRACE request body was omitted"))
                                      [req' nil])

                                    (not multipart?)
                                    [req' body]

                                    :else
                                    (multipart/encode-request req' parts))]

                  (when-let [save-message (get req :aleph/save-request-message)]
                    ;; debug purpose only
                    ;; note, that req' is effectively mutable, so
                    ;; it will "capture" all changes made during "send-message"
                    ;; execution
                    (reset! save-message req'))

                  (when-let [save-body (get req :aleph/save-request-body)]
                    ;; might be different in case we use :multipart
                    (reset! save-body body))

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
                      (instance? Throwable rsp)
                      (d/error-deferred rsp)

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

(defn websocket-client-handler [raw-stream? uri sub-protocols extensions? headers max-frame-payload handshake-timeout]
  (let [d (d/deferred)
        in (atom nil)
        desc (atom {})
        handshaker (websocket-handshaker uri sub-protocols extensions? headers max-frame-payload)
        timeout-task (atom nil)]

    [d

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
         (when-not (d/error! d ex)
           (log/warn ex "error in websocket client"))
         (netty/close ctx))

       :channel-inactive
       ([_ ctx]
         (s/close! @in)
         (when (realized? d)
           ;; close only on success
           (d/chain' d s/close!))
         (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
         (let [ch (.channel ctx)]
           (reset! in (netty/buffered-source ch (constantly 1) 16))

           ;; Start handshake timeout timer
           (reset! timeout-task
                   (.in time/*clock*
                        handshake-timeout
                        (fn []
                          ;; if there was no answer after handshake was sent - close channel
                          (d/error! d (WebSocketHandshakeTimeoutException. "WebSocket handshake timeout"))
                          (netty/close ctx))))

           (.handshake handshaker ch))
         (.fireChannelActive ctx))

       :channel-read
       ([_ ctx msg]
         (try
           (let [ch (.channel ctx)]
             (cond

               (not (.isHandshakeComplete handshaker))
               ;; If timeout task cannot be cancelled there is no need to continue handshake
               (when (.cancel ^Future @timeout-task false)
                 (-> (netty/wrap-future (.processHandshake handshaker ch msg))
                     ;; we want to check timeout here too
                     (d/timeout! handshake-timeout)
                     (d/chain'
                       (fn [_]
                         (let [out (netty/sink ch false
                                               (fn [c]
                                                 (if (instance? CharSequence c)
                                                   (TextWebSocketFrame. (bs/to-string c))
                                                   (BinaryWebSocketFrame. (netty/to-byte-buf ctx c))))
                                               (fn [] @desc))]

                           (d/success! d
                                       (doto
                                         (s/splice out @in)
                                         (reset-meta! {:aleph/channel ch})))

                           (s/on-drained @in
                                         #(when (.isOpen ch)
                                            (d/chain'
                                              (netty/wrap-future (.close handshaker ch (CloseWebSocketFrame.)))
                                              (fn [_] (netty/close ctx))))))))
                     (d/catch'
                       (fn [ex]
                         ;; handle handshake exception
                         (d/error! d ex)
                         (netty/close ctx)))))

               (instance? FullHttpResponse msg)
               (let [rsp ^FullHttpResponse msg]
                 (throw
                   (IllegalStateException.
                     (str "unexpected HTTP response, status: "
                       (.status rsp)
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

               (instance? PingWebSocketFrame msg)
               (let [frame (.content ^PingWebSocketFrame msg)]
                 (netty/write-and-flush  ch (PongWebSocketFrame. (netty/acquire frame))))

               (instance? CloseWebSocketFrame msg)
               (let [frame ^CloseWebSocketFrame msg]
                 (when (realized? d)
                   (swap! desc assoc
                     :websocket-close-code (.statusCode frame)
                     :websocket-close-msg (.reasonText frame)))
                 (netty/close ctx))

               :else
               (.fireChannelRead ctx msg)))
           (finally
             (netty/release msg)))))]))

(defn websocket-connection
  [uri
   {:keys [raw-stream?
           insecure?
           headers
           local-address
           bootstrap-transform
           pipeline-transform
           epoll?
           sub-protocols
           extensions?
           max-frame-payload
           max-frame-size
           handshake-timeout
           compression?]
    :or {bootstrap-transform identity
         pipeline-transform identity
         raw-stream? false
         epoll? false
         sub-protocols nil
         extensions? false
         max-frame-payload 65536
         max-frame-size 1048576
         handshake-timeout 60000
         compression? false}}]
  (let [uri (URI. uri)
        scheme (.getScheme uri)
        _ (assert (#{"ws" "wss"} scheme) "scheme must be one of 'ws' or 'wss'")
        ssl? (= "wss" scheme)
        [s handler] (websocket-client-handler
                      raw-stream?
                      uri
                      sub-protocols
                      extensions?
                      headers
                      max-frame-payload
                      handshake-timeout)]
    (d/chain'
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (doto pipeline
            (.addLast "http-client" (HttpClientCodec.))
            (.addLast "aggregator" (HttpObjectAggregator. 16384))
            (.addLast "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
            (#(when compression?
                (.addLast ^ChannelPipeline %
                          "websocket-deflater"
                          WebSocketClientCompressionHandler/INSTANCE)))
            (.addLast "handler" ^ChannelHandler handler)
            pipeline-transform))
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
