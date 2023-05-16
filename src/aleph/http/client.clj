(ns ^:no-doc aleph.http.client
  (:require
    [aleph.http.common :as http.common]
    [aleph.http.core :as http1]
    [aleph.http.http2 :as http2]
    [aleph.http.multipart :as multipart]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.utils
      ProxyConnectionTimeoutException)
    (io.netty.buffer
      ByteBuf)
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelHandlerContext
      ChannelPipeline)
    (io.netty.handler.codec
      TooLongFrameException)
    (io.netty.handler.codec.http
      DefaultHttpHeaders
      FullHttpResponse
      HttpClientCodec
      HttpContent
      HttpHeaderNames
      HttpRequest
      HttpResponse
      HttpUtil
      LastHttpContent)
    (io.netty.handler.codec.http2
      Http2FrameCodecBuilder
      Http2FrameLogger
      Http2MultiplexHandler
      Http2Settings
      Http2StreamChannel Http2StreamChannelBootstrap
      Http2StreamFrameToHttpObjectCodec)
    (io.netty.handler.logging
      LogLevel
      LoggingHandler)
    (io.netty.handler.proxy
      HttpProxyHandler
      HttpProxyHandler$HttpProxyConnectException
      ProxyConnectException
      ProxyConnectionEvent
      ProxyHandler
      Socks4ProxyHandler
      Socks5ProxyHandler)
    (io.netty.handler.ssl
      ApplicationProtocolConfig
      ApplicationProtocolConfig$Protocol
      ApplicationProtocolConfig$SelectedListenerFailureBehavior
      ApplicationProtocolConfig$SelectorFailureBehavior
      ApplicationProtocolNames
      SslHandler)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (io.netty.util.internal StringUtil)
    (java.io
      IOException)
    (java.net
      IDN
      InetSocketAddress
      URI
      URL)
    (java.util.concurrent.atomic
      AtomicInteger)
    (javax.net.ssl SSLHandshakeException)))

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

  (defn req->domain
    "Returns the URI corresponding to a request"
    ^URI [req]
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

(defn send-response-decoder-failure [^ChannelHandlerContext ctx msg response-stream]
  (let [^Throwable ex (http.common/decoder-failure msg)]
    (s/put! response-stream ex)
    (netty/close ctx)))

(defn handle-decoder-failure [^ChannelHandlerContext ctx msg stream complete response-stream]
  (if (instance? HttpContent msg)
    ;; note that we are most likely to get this when dealing
    ;; with transfer encoding chunked
    (if-let [s @stream]
      (do
        ;; flag that body was not completed succesfully
        (d/success! @complete true)
        (s/close! s))
      (send-response-decoder-failure ctx msg response-stream))
    (send-response-decoder-failure ctx msg response-stream)))

(defn exception-handler [ctx ex response-stream]
  (println "exception-handler" ex)
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
                  (http1/netty-response->ring-response
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
         (http.common/decoder-failed? msg)
         (handle-decoder-failure ctx msg stream complete response-stream)

         (instance? HttpResponse msg)
         (let [rsp msg
               s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)
               c (d/deferred)]

           (reset! stream s)
           (reset! complete c)
           (s/on-closed s #(d/success! c true))
           (handle-response rsp c s))

         (instance? HttpContent msg)
         (let [content (.content ^HttpContent msg)]
           (netty/put! (.channel ctx) @stream content)
           (when (instance? LastHttpContent msg)
             (d/success! @complete false)
             (s/close! @stream)))

         :else
         (.fireChannelRead ctx msg))))))

(defn client-handler
  "Given a response-stream, returns a ChannelInboundHandler that processes
   inbound Netty Http1 objects, converts them, and places them on the stream"
  [response-stream ^long buffer-capacity]
  (let [response (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        body-stream (atom nil)
        complete (atom nil)
        handle-response (fn [rsp complete body]
                          (s/put! response-stream
                                  (http1/netty-response->ring-response
                                    rsp
                                    complete
                                    body)))]

    (netty/channel-inbound-handler

      :exception-caught
      ([_ ctx ex]
       (exception-handler ctx ex response-stream))

      :channel-inactive
      ([_ ctx]
       (when-let [s @body-stream]
         (s/close! s))
       (doseq [b @buffer]
         (netty/release b))
       (s/close! response-stream)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]

       (cond
         (http.common/decoder-failed? msg)
         (handle-decoder-failure ctx msg body-stream complete response-stream)

         ;; happens when io.netty.handler.codec.http.HttpObjectAggregator is part of the pipeline
         (instance? FullHttpResponse msg)
         (let [^FullHttpResponse rsp msg
               content (.content rsp)
               body (netty/buf->array content)]
           (netty/release content)
           (handle-response rsp (d/success-deferred false) body))

         ;; An incomplete and/or chunked response
         ;; Sets up a new stream to put the body chunks on as they come in
         (instance? HttpResponse msg)
         (let [rsp msg]
           (if (HttpUtil/isTransferEncodingChunked rsp)
             (let [s (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)
                   c (d/deferred)]
               (reset! body-stream s)
               (reset! complete c)
               (s/on-closed s #(d/success! c true))
               (handle-response rsp c s))
             (reset! response rsp)))

         ;; Http chunk
         ;; If we have no body stream, make one and put the chunk on it
         ;; Either clean up if we have the last chunk, or save the body
         ;; stream for later chunks
         (instance? HttpContent msg)
         (let [content (.content ^HttpContent msg)]
           (if (instance? LastHttpContent msg)
             (do

               (if-let [s @body-stream]

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
               (reset! body-stream nil)
               (reset! buffer [])
               (reset! response nil))

             (if-let [s @body-stream]

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
                       (reset! body-stream s)
                       (reset! complete c)

                       (s/on-closed s #(d/success! c true))

                       (handle-response @response c s))))))))

         :else
         (do
           (log/warn "Unknown msg class:" (class msg))
           (println "Unknown msg class:" (class msg))
           (.fireChannelRead ctx msg)))))))

(defn non-tunnel-proxy? [{:keys [tunnel? user http-headers ssl?]
                          :as   proxy-options}]
  (and (some? proxy-options)
       (not tunnel?)
       (not ssl?)
       (nil? user)
       (nil? http-headers)))

(defn http-proxy-headers [{:keys [http-headers keep-alive?]
                           :or   {http-headers {}
                                  keep-alive?  true}}]
  (let [headers (DefaultHttpHeaders.)]
    (http1/map->headers! headers http-headers)
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
    :or   {keep-alive? true}
    :as   options}]
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
                      :or   {protocol           :http
                             connection-timeout 6e4}
                      :as   options}]
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
                        (ProxyConnectionTimeoutException. ^Throwable cause)

                        (some? headers)
                        (ex-info message {:headers (http1/headers->map headers)})

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

(defn- ^:no-doc add-non-http-handlers
  "Set up the pipeline with HTTP-independent handlers.

   Includes logger, proxy, and custom pipeline-transform handlers."
  [^ChannelPipeline p response-stream proxy-options ssl? logger pipeline-transform]
  (when (some? proxy-options)
    (let [proxy (proxy-handler (assoc proxy-options :ssl? ssl?))]
      (.addFirst p "proxy" ^ChannelHandler proxy)
      ;; well, we need to wait before the proxy responded with
      ;; HTTP/1.1 200 Connection established
      ;; before sending any requests
      (when (instance? ProxyHandler proxy)
        (.addAfter p
                   "proxy"
                   "pending-proxy-connection"
                   ^ChannelHandler
                   (pending-proxy-connection-handler response-stream)))))

  (when (some? logger)
    (println "Adding activity logger")
    (.addFirst p "activity-logger" ^ChannelHandler logger))

  (pipeline-transform p)
  p)

(let [stream-frame->http-object-codec (delay (Http2StreamFrameToHttpObjectCodec. false))]
  (defn- h2-stream-chan-initializer
    "The multiplex handler creates a channel per HTTP2 stream, this
     sets up each new stream channel"
    [response-stream proxy-options ssl? logger pipeline-transform handler]
    (println "h2-stream-chan-initializer called") (flush)
    (netty/pipeline-initializer
      (fn [^ChannelPipeline p]
        (log/trace "h2-stream-chan-initializer initChannel called")

        (.addLast p
                  "stream-frame-to-http-object"
                  ^Http2StreamFrameToHttpObjectCodec @stream-frame->http-object-codec)
        (.addLast p
                  "handler"
                  ^ChannelHandler handler)

        (add-non-http-handlers
          p
          response-stream
          proxy-options
          ssl?
          logger
          pipeline-transform)
        (log/trace "added all stream-chan handlers")

        (log/debug (str "Stream chan pipeline:" (prn-str p)))))))


(defn- setup-http-pipeline
  "Sets up the pipeline for the appropriate HTTP version"
  [{:keys
    [protocol
     handler
     logger
     pipeline-transform
     max-initial-line-length
     max-header-size
     max-chunk-size
     proxy-options
     ssl?
     idle-timeout
     response-stream
     ^ChannelPipeline pipeline]
    :or
    {pipeline-transform      identity
     max-initial-line-length 65536
     max-header-size         65536
     max-chunk-size          65536
     idle-timeout            0
     protocol                ApplicationProtocolNames/HTTP_1_1}
    :as opts}]
  (log/info (str "Negotiated protocol: " protocol))
  (println (str "Negotiated protocol: " protocol))

  (log/debug "Logger:" logger " - log level: " (some-> logger .level))
  (println "Logger:" logger " - log level: " (some-> logger .level))

  ;; because case doesn't work with Java constants
  (cond
    (.equals ApplicationProtocolNames/HTTP_1_1 protocol)
    (-> pipeline
        (http.common/attach-idle-handlers idle-timeout)
        (.addLast "http-client"
                  (HttpClientCodec.
                    max-initial-line-length
                    max-header-size
                    max-chunk-size
                    false
                    false))
        (.addLast "streamer" ^ChannelHandler (ChunkedWriteHandler.))
        (.addLast "handler" ^ChannelHandler handler)
        (add-non-http-handlers
          response-stream
          proxy-options
          ssl?
          logger
          pipeline-transform))

    (.equals ApplicationProtocolNames/HTTP_2 protocol)
    (let [log-level (some-> logger (.level))
          ;; TODO: share betw pipelines
          http2-frame-codec (let [builder (Http2FrameCodecBuilder/forClient)]
                              (when log-level
                                (.frameLogger builder (Http2FrameLogger. log-level)))
                              (-> builder
                                  (.initialSettings (Http2Settings/defaultSettings))
                                  (.build)))

          ;; For the client, this may never get used, since the server will rarely
          ;; initiate streams, and PUSH_PROMISE is deprecated. Responses to client-
          ;; initiated streams use a separate handler, though we *also* set that using
          ;; h2-stream-chan-initializer. Regardless, Http2MultiplexHandler must be on
          ;; the pipeline to get new outbound channels.
          server-initiated-stream-chan-initializer
          (h2-stream-chan-initializer
            response-stream proxy-options ssl? logger pipeline-transform handler)

          multiplex-handler (Http2MultiplexHandler. server-initiated-stream-chan-initializer)]



      (-> pipeline
          (http.common/attach-idle-handlers idle-timeout)
          (.addLast "http2-frame-codec" http2-frame-codec)
          (.addLast "multiplex" multiplex-handler))

      (println "Conn chan pipeline:" (prn-str pipeline)))

    :else
    (do
      (log/error (str "Unsupported SSL protocol: " protocol))
      (println (str "Unsupported SSL protocol: " protocol))
      (throw (IllegalStateException. (str "Unsupported SSL protocol: " protocol))))))

;; TODO: add SSL handler in here.
(defn make-pipeline-builder
  "Returns a function that initializes a new channel's pipeline.

   For HTTP/2 multiplexing, does not set up child channel pipelines. See
   `h2-stream-chan-initializer` for that.

   SSL/TLS is handled with `add-ssl-handler`."
  [response-stream
   {:keys [ssl? apn-handler-removed]
    :as   opts}]
  (fn pipeline-builder
    [^ChannelPipeline pipeline]
    (if ssl?
      ;; when making an SSL request while supporting multiple HTTP versions,
      ;; the client and server negotiate which version to use, and we can't
      ;; finish the pipeline until that happens
      (do
        (.addLast pipeline
                  "apn-handler"
                  (netty/application-protocol-negotiation-handler
                    (fn configure-pipeline
                      [^ChannelHandlerContext ctx protocol]
                      (setup-http-pipeline (assoc opts
                                                  :response-stream response-stream
                                                  :pipeline (.pipeline ctx)
                                                  :protocol protocol)))
                    ApplicationProtocolNames/HTTP_1_1
                    apn-handler-removed))
        (println "ALPN setup: " (prn-str pipeline)))

      (do
        (setup-http-pipeline (assoc opts
                                    :response-stream response-stream
                                    :pipeline pipeline))
        (d/success! apn-handler-removed true)))))

(defn close-connection [f]
  (f
    {:method :get
     :url    "http://example.com"
     ::close true}))

;; includes host into URI for requests that go through proxy
(defn req->proxy-url [{:keys [uri] :as req}]
  (let [^URI uri' (req->domain req)]
    (str (URI. (.getScheme uri')
               nil
               (.getHost uri')
               (.getPort uri')
               uri
               nil
               nil))))



(defn- rsp-handler
  "Returns a fn that takes a response map and returns the final Ring response map.

   Handles errors, closing, and converts the body if not raw."
  [{:keys [ch keep-alive? raw-stream? req response-buffer-size t0]}]
  (fn handle-response [rsp]
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
                ;; chain, since getting locks and conversion can be expensive?
                (fn handle-body-stream [rsp]
                  (let [body (:body rsp)]
                    ;; handle connection life-cycle
                    (when-not keep-alive?
                      (if (s/stream? body)
                        (s/on-closed body #(netty/close ch))
                        (netty/close ch)))

                    ;; If it's not raw, convert the body to an InputStream
                    (assoc rsp
                           :body
                           (bs/to-input-stream body
                                               {:buffer-size response-buffer-size}))))))))

(defn- req-handler
  "Returns a fn that takes a Ring request and returns a deferred containing a
   Ring response.

   If ::close is set in the req, closes the channel and returns a deferred containing
   the result.

   Otherwise, puts/takes to/from the requests/responses streams."
  [{:keys [ch keep-alive? raw-stream? requests response-buffer-size responses]}]
  (let [t0 (System/nanoTime)]
    (fn [req]
      (println "req-handler fired")
      (if (contains? req ::close)
        (netty/wrap-future (netty/close ch))
        (let [raw-stream? (get req :raw-stream? raw-stream?)
              rsp (locking ch
                    (s/put! requests req)
                    (s/take! responses ::closed))]
          (d/chain' rsp
                    (rsp-handler
                      {:ch                   ch
                       :keep-alive?          keep-alive?
                       :raw-stream?          raw-stream?
                       :req                  req
                       :response-buffer-size response-buffer-size
                       :t0                   t0})))))))


(defn- req-preprocesser
  "Returns a fn that preprocesses Ring reqs off the requests stream, and sends
   them and their bodies off to `send-message` (HTTP/1.1) or `req-preprocess` (HTTP/2).

   Converts a Ring req to Netty-friendly objects, updates headers, encodes for
   multipart reqs, and records debug vals."
  [{:keys [ch protocol responses ssl? authority] :as opts}]
  (cond
    (.equals ApplicationProtocolNames/HTTP_1_1 protocol)
    (let [{:keys [keep-alive?' non-tun-proxy?]} opts]
      (fn [req]
        (try
          (let [^HttpRequest req' (http/ring-request->netty-request
                                    (if non-tun-proxy?
                                      (assoc req :uri (req->proxy-url req))
                                      req))]
            (when-not (.get (.headers req') "Host")
              (.set (.headers req') HttpHeaderNames/HOST authority))
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
            (netty/close ch)))))

    (.equals ApplicationProtocolNames/HTTP_2 protocol)
    (let [h2-bootstrap (Http2StreamChannelBootstrap. ch)
          {:keys [proxy-options logger pipeline-transform handler]} opts]

      ;; when you create an HTTP2 outbound stream, you have to supply it with a
      ;; handler for the response
      (.handler h2-bootstrap
                (h2-stream-chan-initializer
                  responses proxy-options ssl? logger pipeline-transform handler))

      (fn [req]
        (println "req-preprocesser h2 fired")

        (let [req' (cond-> req
                           (nil? (:authority req))
                           (assoc :authority authority)

                           (nil? (:uri req))
                           (assoc :uri "/")

                           (nil? (:scheme req))
                           (assoc :scheme (if ssl? :https :http)))]
          (-> (.open h2-bootstrap)
              netty/wrap-future
              (d/chain' (fn [^Http2StreamChannel chan]
                          (println "Got outbound h2 stream.")

                          (-> chan
                              .pipeline
                              (.addLast "debug"
                                        (netty/channel-inbound-handler
                                          :channel-read ([_ ctx msg]
                                                         (println "received msg of class" (class msg))
                                                         (println "msg:" msg)))))
                          (http2/req-preprocess chan req' responses)))
              (d/catch' (fn [^Throwable t]
                          (log/error t "Unable to open outbound HTTP/2 stream channel")
                          (println "Unable to open outbound HTTP/2 stream channel")
                          (.printStackTrace t)
                          (s/put! responses (d/error-deferred t))
                          (netty/close ch)))))))

    :else
    (do
      (let [msg (str "Unknown protocol: " protocol)
            e (SSLHandshakeException. msg)]
        (log/error e msg)
        (println msg protocol)
        (netty/close ch)
        (s/put! responses (d/error-deferred e))))))


(defn http-connection
  "Returns a deferred containing a fn that accepts a Ring request and returns
   a deferred containing a Ring response."
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
           transport
           proxy-options
           pipeline-transform
           log-activity
           http-versions]
    :or   {raw-stream? false
           bootstrap-transform  identity
           pipeline-transform   identity
           keep-alive?          true
           response-buffer-size 65536
           epoll?               false
           name-resolver        :default
           log-activity         :debug
           http-versions        [ApplicationProtocolNames/HTTP_2
                                 ApplicationProtocolNames/HTTP_1_1]}
    :as   options}]
  (let [responses (s/stream 1024 nil response-executor)
        _ (s/on-closed responses #(println "responses closed."))
        requests (s/stream 1024 nil nil)
        host (.getHostName remote-address)
        port (.getPort remote-address)
        explicit-port? (and (pos? port) (not= port (if ssl? 443 80)))
        proxy-options' (when (some? proxy-options)
                         (assoc proxy-options :ssl? ssl?))
        non-tun-proxy? (non-tunnel-proxy? proxy-options')
        keep-alive?' (boolean (or keep-alive? (when (some? proxy-options)
                                                (get proxy-options :keep-alive? true))))
        authority (str host (when explicit-port? (str ":" port)))

        ssl-context (when ssl?
                      (or ssl-context
                          (if insecure?
                            (netty/insecure-ssl-client-context)
                            (let [application-protocol-config
                                  (ApplicationProtocolConfig.
                                    ApplicationProtocolConfig$Protocol/ALPN
                                    ;; NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                                    ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
                                    ;; ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                                    ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
                                    ^"[Ljava.lang.String;"
                                    (into-array String http-versions))]
                              (netty/ssl-client-context
                                {:application-protocol-config
                                 application-protocol-config})))))
        apn-handler-removed (d/deferred)

        logger (cond
                 (instance? LoggingHandler log-activity) log-activity
                 (some? log-activity) (netty/activity-logger "aleph-client" log-activity)
                 :else nil)

        handler (if raw-stream?
                  (raw-client-handler responses response-buffer-size)
                  (client-handler responses response-buffer-size))

        pipeline-builder (make-pipeline-builder responses
                                                (assoc options
                                                       :ssl? ssl?
                                                       :handler handler
                                                       :logger logger
                                                       :apn-handler-removed apn-handler-removed
                                                       :pipeline-transform pipeline-transform))

        ch (netty/create-client-chan
             {:pipeline-builder    pipeline-builder
              :ssl-context         ssl-context
              :bootstrap-transform bootstrap-transform
              :remote-address      remote-address
              :local-address       local-address
              :transport           (netty/determine-transport transport epoll?)
              :name-resolver       name-resolver})]
    (d/chain' ch
              (fn setup-client
                [^Channel ch]

                ;; Order: req map -> req-handler -> requests stream -> req-preprocesser
                ;; -> send-message -> Netty ->
                ;; ... Internet ...
                ;; -> Netty -> responses stream -> req-handler -> rsp-handler -> rsp map

                (s/on-closed responses
                             (fn []
                               (when on-closed (on-closed))
                               (s/close! requests)))

                ;; We know the SSL handshake must be complete because create-client wraps the
                ;; future with maybe-ssl-handshake-future, so we can get the negotiated
                ;; protocol, falling back to HTTP/1.1 by default. However, the ALPN handler
                ;; may still be present on the pipeline, so we can't start req-preprocessor yet
                (let [protocol (if ssl?
                                 (or (-> ch
                                         (.pipeline)
                                         ^SslHandler (.get ^Class SslHandler)
                                         (.applicationProtocol))
                                     ApplicationProtocolNames/HTTP_1_1)
                                 ApplicationProtocolNames/HTTP_1_1)]
                  (println "HTTP protocol:" protocol)
                  (println "post-handshake channel?" (prn-str ch))

                  (d/on-realized apn-handler-removed
                                 (fn [_]
                                   (println "Beginning request consumption") (flush)
                                   (s/consume
                                     (req-preprocesser {:ch                 ch
                                                        :protocol           protocol
                                                        :authority          authority
                                                        :keep-alive?'       keep-alive?'
                                                        :non-tun-proxy?     non-tun-proxy?
                                                        :responses          responses
                                                        :ssl?               ssl?
                                                        :proxy-options      proxy-options
                                                        :logger             logger
                                                        :pipeline-transform pipeline-transform
                                                        :handler            handler})
                                     requests))
                                 (fn [^Throwable t]
                                   (log/error t "Error removing ALPN handler")
                                   (println "Error removing ALPN handler" t) (flush)
                                   (s/close! responses)
                                   (s/close! requests)
                                   (.close ch)))

                  (req-handler {:ch                   ch
                                :keep-alive?          keep-alive? ; why not keep-alive?'
                                :raw-stream?          raw-stream?
                                :requests             requests
                                :response-buffer-size response-buffer-size
                                :responses            responses}))))))



(comment

  (do
    (def conn @(http-connection
                 (InetSocketAddress/createUnresolved "www.google.com" (int 443))
                 true
                 {:on-closed #(println "http conn closed")}))

    (conn {:request-method :get}))
  )
