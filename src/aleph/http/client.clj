(ns aleph.http.client
  (:require
    [clojure.tools.logging :as log]
    [clj-commons.byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http.core :as http]
    [aleph.http.multipart :as multipart]
    [aleph.netty :as netty])
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
    [io.netty.handler.codec
     TooLongFrameException]
    [io.netty.handler.timeout
     IdleState
     IdleStateEvent]
    [io.netty.handler.stream
     ChunkedWriteHandler]
    [io.netty.handler.codec.http
     FullHttpRequest]
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
     HttpProxyHandler$HttpProxyConnectException
     Socks4ProxyHandler
     Socks5ProxyHandler]
    [io.netty.handler.logging
     LoggingHandler]
    [java.util.concurrent
     ConcurrentLinkedQueue]
    [java.util.concurrent.atomic
     AtomicInteger
     AtomicBoolean]
    [aleph.utils
     ProxyConnectionTimeoutException]))

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

(defn exception-handler [ctx ex response-stream]
  (cond
    ;; could happens when io.netty.handler.codec.http.HttpObjectAggregator
    ;; is part of the pipeline
    (instance? TooLongFrameException ex)
    (s/put! response-stream ex)

    ;; when SSL handshake failed
    (netty/ssl-handshake-error? ex)
    (let [^Throwable handshake-error (.getCause ^Throwable ex)]
      (s/put! response-stream handshake-error))

    (not (instance? IOException ex))
    (log/warn ex "error in HTTP client")))

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
        (exception-handler ctx ex response-stream))

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
        (exception-handler ctx ex response-stream))

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

          ; happens when io.netty.handler.codec.http.HttpObjectAggregator is part of the pipeline
          (instance? FullHttpResponse msg)
          (let [^FullHttpResponse rsp msg
                content (.content rsp)
                body (netty/buf->array content)]
            (netty/release content)
            (handle-response rsp (d/success-deferred false) body))

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
        (let [message (.getMessage ^Throwable cause)
              headers (when (instance? HttpProxyHandler$HttpProxyConnectException cause)
                        (.headers ^HttpProxyHandler$HttpProxyConnectException cause))
              response (cond
                         (= "timeout" message)
                         (ProxyConnectionTimeoutException. cause)

                         (some? headers)
                         (ex-info message {:headers (http/headers->map headers)})

                         :else
                         cause)]
          (s/put! response-stream response)
          ;; client handler should take care of the rest
          (netty/close ctx))))

    :user-event-triggered
    ([this ctx evt]
      (when (instance? ProxyConnectionEvent evt)
        (.remove (.pipeline ctx) this))
      (.fireUserEventTriggered ^ChannelHandlerContext ctx evt))))

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
          logger (cond
                   (instance? LoggingHandler log-activity)
                   log-activity

                   (some? log-activity)
                   (netty/activity-logger "aleph-client" log-activity)

                   :else
                   nil)]
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
        (.addFirst pipeline "activity-logger" ^ChannelHandler logger))
      (pipeline-transform pipeline))))

(defn close-connection [f]
  (f
    {:method :get
     :url "http://example.com"
     ::close true}))

;; includes host into URI for requests that go through proxy
(defn req->proxy-url [{:keys [uri] :as req}]
  (let [^URI uri' (req->domain req)]
    (.toString (URI. (.getScheme uri')
                     nil
                     (.getHost uri')
                     (.getPort uri')
                     uri
                     nil
                     nil))))

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
        proxy-options' (when (some? proxy-options)
                         (assoc proxy-options :ssl? ssl?))
        non-tunnel-proxy? (non-tunnel-proxy? proxy-options')
        keep-alive?' (boolean (or keep-alive? (when (some? proxy-options)
                                                (get proxy-options :keep-alive? true))))
        host-header-value (str host (when explicit-port? (str ":" port)))
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
                      (let [^HttpRequest req' (http/ring-request->netty-request
                                                (if non-tunnel-proxy?
                                                  (assoc req :uri (req->proxy-url req))
                                                  req))]
                        (when-not (.get (.headers req') "Host")
                          (.set (.headers req') HttpHeaderNames/HOST host-header-value))
                        (when-not (.get (.headers req') "Connection")
                          (HttpUtil/setKeepAlive req' keep-alive?'))

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

                          (-> (netty/safe-execute ch
                                (http/send-message ch true ssl? req' body))
                              (d/catch' (fn [e]
                                          (s/put! responses (d/error-deferred e))
                                          (netty/close ch))))))

                      ;; this will usually happen because of a malformed request
                      (catch Throwable e
                        (s/put! responses (d/error-deferred e))
                        (netty/close ch))))
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

(defn websocket-client-handler
  ([raw-stream?
    uri
    sub-protocols
    extensions?
    headers
    max-frame-payload]
   (websocket-client-handler raw-stream?
                             uri
                             sub-protocols
                             extensions?
                             headers
                             max-frame-payload
                             nil))
  ([raw-stream?
    uri
    sub-protocols
    extensions?
    headers
    max-frame-payload
    heartbeats]
   (let [d (d/deferred)
         in (atom nil)
         desc (atom {})
         ^ConcurrentLinkedQueue pending-pings (ConcurrentLinkedQueue.)
         handshaker (websocket-handshaker uri
                                          sub-protocols
                                          extensions?
                                          headers
                                          max-frame-payload)
         closing? (AtomicBoolean. false)]

     [d

      (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
        (when-not (d/error! d ex)
          (log/warn ex "error in websocket client"))
        (s/close! @in)
        (netty/close ctx))

       :channel-inactive
       ([_ ctx]
        (when (realized? d)
          ;; close only on success
          (d/chain' d s/close!)
          (http/resolve-pings! pending-pings false))
        (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
        (-> (.channel ctx)
            netty/maybe-ssl-handshake-future
            (d/on-realized (fn [ch]
                             (reset! in (netty/buffered-source ch (constantly 1) 16))
                             (.handshake handshaker ch))
                           netty/ignore-ssl-handshake-errors))
        (.fireChannelActive ctx))

       :user-event-triggered
       ([_ ctx evt]
        (if (and (instance? IdleStateEvent evt)
                 (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
          (when (d/realized? d)
            (http/handle-heartbeat ctx @d heartbeats))
          (.fireUserEventTriggered ctx evt)))

       :channel-read
       ([_ ctx msg]
        (let [ch (.channel ctx)]
          (cond

            (not (.isHandshakeComplete handshaker))
            (try
              ;; Here we rely on the HttpObjectAggregator being added
              ;; to the pipeline in advance, so there's no chance we
              ;; could read only a partial request
              (.finishHandshake handshaker ch msg)
              (let [close-fn (fn [^CloseWebSocketFrame frame]
                               (if-not (.compareAndSet closing? false true)
                                 (do
                                   (netty/release frame)
                                   false)
                                 (do
                                   (-> (.close handshaker ch frame)
                                       netty/wrap-future
                                       (d/chain' (fn [_] (netty/close ctx))))
                                   true)))
                    coerce-fn (http/websocket-message-coerce-fn
                               ch
                               pending-pings
                               close-fn)
                    headers (http/headers->map (.headers ^HttpResponse msg))
                    subprotocol (.actualSubprotocol handshaker)
                    _ (swap! desc assoc
                             :websocket-handshake-headers headers
                             :websocket-selected-subprotocol subprotocol)
                    out (netty/sink ch false coerce-fn (fn [] @desc))]

                (s/on-closed out #(http/resolve-pings! pending-pings false))

                (d/success! d
                            (doto
                                (s/splice out @in)
                              (reset-meta! {:aleph/channel ch})))

                (s/on-drained @in #(close-fn (CloseWebSocketFrame.))))
              (catch Throwable ex
                ;; handle handshake exception
                (d/error! d ex)
                (s/close! @in)
                (netty/close ctx))
              (finally
                (netty/release msg)))

            (instance? FullHttpResponse msg)
            (let [rsp ^FullHttpResponse msg
                  content (bs/to-string (.content rsp))]
              (netty/release msg)
              (throw
               (IllegalStateException.
                (str "unexpected HTTP response, status: "
                     (.status rsp)
                     ", body: '"
                     content
                     "'"))))

            (instance? TextWebSocketFrame msg)
            (if raw-stream?
              (let [body (.content ^TextWebSocketFrame msg)]
                ;; pass ByteBuf body directly to lower next
                ;; level. it's their reponsibility to release
                (netty/put! ch @in body))
              (let [text (.text ^TextWebSocketFrame msg)]
                (netty/release msg)
                (netty/put! ch @in text)))

            (instance? BinaryWebSocketFrame msg)
            (let [frame (.content ^BinaryWebSocketFrame msg)]
              (netty/put! ch @in
                          (if raw-stream?
                            frame
                            (netty/release-buf->array frame))))

            (instance? PongWebSocketFrame msg)
            (do
              (netty/release msg)
              (http/resolve-pings! pending-pings true))

            (instance? PingWebSocketFrame msg)
            (let [frame (.content ^PingWebSocketFrame msg)]
              (netty/write-and-flush  ch (PongWebSocketFrame. frame)))

            ;; todo(kachayev): check RFC what should we do in case
            ;;                 we've got > 1 closing frame from the
            ;;                 server
            (instance? CloseWebSocketFrame msg)
            (let [frame ^CloseWebSocketFrame msg]
              (when (realized? d)
                (swap! desc assoc
                       :websocket-close-code (.statusCode frame)
                       :websocket-close-msg (.reasonText frame)))
              (netty/release msg)
              (netty/close ctx))

            :else
            (.fireChannelRead ctx msg)))))])))

(defn websocket-connection
  [uri
   {:keys [raw-stream?
           insecure?
           ssl-context
           headers
           local-address
           bootstrap-transform
           pipeline-transform
           epoll?
           sub-protocols
           extensions?
           max-frame-payload
           max-frame-size
           compression?
           heartbeats]
    :or {bootstrap-transform identity
         pipeline-transform identity
         raw-stream? false
         epoll? false
         sub-protocols nil
         extensions? false
         max-frame-payload 65536
         max-frame-size 1048576
         compression? false}
    :as options}]

  (when (and (true? (:compression? options))
             (false? (:extensions? options)))
    (throw (IllegalArgumentException.
            "Per-message deflate requires extensions to be allowed")))

  (let [uri (URI. uri)
        scheme (.getScheme uri)
        _ (assert (#{"ws" "wss"} scheme) "scheme must be one of 'ws' or 'wss'")
        ssl? (= "wss" scheme)
        heartbeats (when (some? heartbeats)
                     (merge
                      {:send-after-idle 3e4
                       :payload nil
                       :timeout nil}
                      heartbeats))
        [s handler] (websocket-client-handler
                      raw-stream?
                      uri
                      sub-protocols
                      (or extensions? compression?)
                      headers
                      max-frame-payload
                      heartbeats)]
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
            (http/attach-heartbeats-handler heartbeats)
            (.addLast "handler" ^ChannelHandler handler)
            pipeline-transform))
        (when ssl?
          (or ssl-context
            (if insecure?
              (netty/insecure-ssl-client-context)
              (netty/ssl-client-context))))
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
