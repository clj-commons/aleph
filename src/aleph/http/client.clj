(ns ^:no-doc aleph.http.client
  (:require
    [aleph.http.common :as common]
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
      Http2StreamChannel
      Http2StreamChannelBootstrap)
    (io.netty.handler.logging
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
      SslContext
      SslHandler)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (java.io
      IOException)
    (java.net
      IDN
      InetSocketAddress
      URI
      URL)
    (java.util.concurrent.atomic
      AtomicInteger)))

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
    "Returns the URI corresponding to a request's scheme, host, and port"
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

(defn exception-handler [ctx ex response-stream]
  (log/warn "exception-handler" ex)
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

(defn http1-raw-client-handler
  "Make a new HTTP/1 raw client handler. Shared across all requests for one TCP conn."
  [response-stream buffer-capacity]
  (let [body-stream (atom nil)
        ;; complete holds latest deferred indicating overall TCP conn status
        ;; Each returned response indicates whether TCP channel was closed or not.
        ;; Used as early? param when deciding whether to dispose or release of conn.
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
       (when-let [s @body-stream]
         (s/close! s))
       (s/close! response-stream)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
       (cond
         (common/decoder-failed? msg)
         (http1/handle-decoder-failure ctx msg body-stream complete response-stream)

         ;; new response coming in
         (instance? HttpResponse msg)
         (let [rsp msg
               s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)
               c (d/deferred)]

           (reset! body-stream s)
           (reset! complete c)
           ; if stream closed for some reason, set complete true to indicate early termination
           (s/on-closed s #(d/success! c true))
           (handle-response rsp c s))

         ;; new chunk of body
         (instance? HttpContent msg)
         (let [content (.content ^HttpContent msg)]
           (netty/put! (.channel ctx) @body-stream content)
           (when (instance? LastHttpContent msg)
             ; false => conn not ended early, we got all the data
             (d/success! @complete false)
             (s/close! @body-stream)))

         :else
         (.fireChannelRead ctx msg))))))

(defn http1-client-handler
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
       (log/trace "http1-client-handler channel-inactive fired")
       (when-let [s @body-stream]
         (s/close! s))
       (doseq [b @buffer]
         (netty/release b))
       (s/close! response-stream)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
       (cond
         (common/decoder-failed? msg)
         (http1/handle-decoder-failure ctx msg body-stream complete response-stream)

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

(defn- add-proxy-handlers
  "Inserts handlers for proxying through a server"
  [^ChannelPipeline p response-stream proxy-options ssl?]
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
  p)

(defn- setup-http1-pipeline
  [{:keys
    [logger
     pipeline-transform
     max-initial-line-length
     max-header-size
     max-chunk-size
     proxy-options
     ssl?
     idle-timeout
     responses
     raw-stream?
     response-buffer-size
     ^ChannelPipeline pipeline]
    :or
    {pipeline-transform      identity
     max-initial-line-length 65536
     max-header-size         65536
     max-chunk-size          65536
     idle-timeout            0}}]
  (let [handler (if raw-stream?
                  (http1-raw-client-handler responses response-buffer-size)
                  (http1-client-handler responses response-buffer-size))]

    (-> pipeline
        (netty/add-idle-handlers idle-timeout)
        (.addLast "http-client"
                  (HttpClientCodec.
                    max-initial-line-length
                    max-header-size
                    max-chunk-size
                    false
                    false))
        (.addLast "streamer" ^ChannelHandler (ChunkedWriteHandler.))
        (.addLast "handler" ^ChannelHandler handler)
        (add-proxy-handlers responses proxy-options ssl?)
        (common/add-non-http-handlers logger pipeline-transform))))


(defn make-pipeline-builder
  "Returns a function that initializes a new conn channel's pipeline.

   Only adds universal handlers like SslHandler. Protocol-specific handlers
   are added later, for ease of coordination with non-Netty setup.

   Can't use an ApnHandler/ApplicationProtocolNegotiationHandler here,
   because it's tricky to run Manifold code on Netty threads."
  [{:keys [ssl? remote-address ssl-context]}]
  (fn pipeline-builder
    [^ChannelPipeline pipeline]
    (when ssl?
      (do
        (.addLast pipeline
                  "ssl-handler"
                  (netty/ssl-handler (.channel pipeline) ssl-context remote-address))
        (.addLast pipeline
                  "pause-handler"
                  ^ChannelHandler (netty/pause-handler))))))

(defn close-connection [f]
  (f
    {:method :get
     :url    "http://closing.aleph.invalid"
     :aleph/close true}))

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

   Shared between HTTP versions.
   Handles errors, closing, and converts the body if not raw."
  [{:keys [ch keep-alive? raw-stream? req response-buffer-size t0]}]
  (fn handle-response [rsp]
    (cond
      (instance? Throwable rsp)
      (d/error-deferred rsp)

      (identical? :aleph/closed rsp)
      (d/error-deferred
        (ex-info
          (format "connection was closed after %.3f seconds" (/ (- (System/nanoTime) t0) 1e9))
          {:request req}))

      raw-stream?
      rsp

      :else
      (d/chain' rsp
                ;; chain, since conversion to InputStream may take time
                (fn convert-body [rsp]
                  (let [body (:body rsp)]
                    ;; handle connection life-cycle
                    (when (false? keep-alive?)              ; assume true if nil
                      (if (s/stream? body)
                        (s/on-closed body #(netty/close ch))
                        (netty/close ch)))

                    ;; Since it's not raw, convert the body to an InputStream
                    (assoc rsp
                           :body
                           (bs/to-input-stream body
                                               {:buffer-size response-buffer-size}))))))))

(defn- http1-req-handler
  "Returns a fn that takes a Ring request and returns a deferred containing the
   Ring response.

   Puts to the requests stream, then takes from the responses stream. (This
   works since HTTP/1 requests and responses are always in order.)"
  [{:keys [ch requests responses]}]
  (fn [req]
    (log/trace "http1-req-handler fired")
    (let [resp (locking ch
                (s/put! requests req)
                (s/take! responses :aleph/closed))]
      (log/debug "http1-req-handler - response: " resp)
      resp)))


(defn- make-http1-req-preprocessor
  "Returns a fn that handles a Ring req map using the HTTP/1 objects.

   Used for HTTP/1, and for HTTP/2 with multipart requests (Netty HTTP/2
   code doesn't support multipart)."
  [{:keys [authority ch keep-alive?' non-tun-proxy? responses ssl?]}]
  (fn http1-req-preprocess [req]
    (try
      (let [^HttpRequest req' (http1/ring-request->netty-request
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
                                  (http1/send-message ch true ssl? req' body))
              (d/catch' (fn [e]
                          (log/error e "Error in http1-req-preprocess")
                          (s/put! responses (d/error-deferred e))
                          (netty/close ch))))))

      ;; this will usually happen because of a malformed request
      (catch Throwable e
        (log/error e "Error in http1-req-preprocess")
        (s/put! responses (d/error-deferred e))
        (netty/close ch)))))


(defn- http2-req-handler
  "Returns a fn that takes a Ring request and returns a deferred containing a
   Ring response."
  [{:keys [authority
           ch
           is-server?
           logger
           pipeline-transform
           proxy-options
           ssl?
           raw-stream?
           response-buffer-size
           stream-go-away-handler
           reset-stream-handler]}]
  (let [h2-bootstrap (Http2StreamChannelBootstrap. ch)]
    (fn [req]
      (log/trace "http2-req-handler fired")

      (let [resp (d/deferred)
            raw-stream? (get req :raw-stream? raw-stream?)
            req' (cond-> req
                         ;; http2 uses :authority, not host
                         (nil? (:authority req))
                         (assoc :authority authority)

                         ;; http2 cannot leave the path empty
                         (nil? (:uri req))
                         (assoc :uri "/")

                         (nil? (:scheme req))
                         (assoc :scheme (if ssl? :https :http)))]

        ;; create a new outbound HTTP2 stream/channel
        (-> (.open h2-bootstrap)
            netty/wrap-future
            (d/chain' (fn [^Http2StreamChannel out-chan]
                        (log/debug "New outbound HTTP/2 channel available.")

                        (http2/setup-stream-pipeline
                          (.pipeline out-chan)
                          (http2/client-handler out-chan
                                                resp
                                                raw-stream?
                                                response-buffer-size
                                                stream-go-away-handler
                                                reset-stream-handler)
                          is-server?
                          proxy-options
                          logger
                          pipeline-transform
                          nil)
                        out-chan))
            (d/chain' (fn [^Http2StreamChannel out-chan]
                        (log/debug "New outbound HTTP/2 channel's pipeline configured.")

                        (if (multipart/is-multipart? req)
                          (let [emsg "Multipart requests are not yet supported in HTTP/2"
                                ex (ex-info emsg {:req req})]
                            (log/error ex emsg)
                            (throw ex))
                          #_(@multipart-req-preprocess (assoc req :ch out-chan)) ; switch to HTTP1 code for multipart
                          (http2/send-request out-chan req' resp))))
            (d/catch' (fn [^Throwable t]
                        (log/error t "Unable to open outbound HTTP/2 stream channel")
                        (d/error! resp t)
                        (netty/close ch))))

        resp))))

(defn- client-ssl-context
  "Returns a client SslContext, or nil if none is requested.
   Validates the ALPN setup."
  ^SslContext
  [ssl? ssl-context desired-protocols insecure?]
  (if ssl?
    (if ssl-context
      (let [^SslContext ssl-ctx (netty/coerce-ssl-client-context ssl-context)]
        ;; check that ALPN is set up if HTTP/2 is requested (https://www.rfc-editor.org/rfc/rfc9113.html#section-3.3)_
        (if (and (-> ssl-ctx
                     (.applicationProtocolNegotiator)
                     (.protocols)
                     (.contains ApplicationProtocolNames/HTTP_2)
                     not)
                 (some #(= ApplicationProtocolNames/HTTP_2 %) desired-protocols))
          (let [emsg "HTTP/2 has been requested, but the required ALPN is not configured properly."
                ex (ex-info emsg {:ssl-context ssl-context})]
            (log/error ex emsg)
            (throw ex))
          ssl-ctx))

      ;; otherwise, use a good default
      (let [ssl-ctx-opts {:application-protocol-config
                          (ApplicationProtocolConfig.
                            ApplicationProtocolConfig$Protocol/ALPN
                            ;; NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
                            ;; ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                            ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
                            ^"[Ljava.lang.String;"
                            (into-array String desired-protocols))}]
        (if insecure?
          (netty/insecure-ssl-client-context ssl-ctx-opts)
          (netty/ssl-client-context ssl-ctx-opts))))
    nil))

(defn- setup-http1-client
  [{:keys [on-closed response-executor]
    :as opts}]
  (let [requests (doto (s/stream 1024 nil nil)
                       (s/on-closed #(log/debug "requests stream closed.")))
        responses (doto (s/stream 1024 nil response-executor)
                        (s/on-closed
                          (fn []
                            (log/debug "responses stream closed.")
                            (when on-closed (on-closed))
                            (s/close! requests))))
        opts (assoc opts
                    :requests requests
                    :responses responses)]

    (setup-http1-pipeline opts)

    ;; HTTP/1 order: req map -> req-handler -> requests stream
    ;; -> req-preprocesser -> send-message -> Netty ->
    ;; ... Internet ...
    ;; -> Netty -> responses stream -> req-handler -> rsp-handler -> rsp map

    ;; preprocess, then feed incoming messages from requests to netty
    (s/consume (make-http1-req-preprocessor opts) requests)

    ;; return user-facing fn that processes Ring maps, and places them on
    ;; requests stream
    (http1-req-handler opts)))

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
           epoll?
           transport
           proxy-options
           pipeline-transform
           log-activity
           http-versions
           force-h2c?]
    :or   {raw-stream?          false
           bootstrap-transform  identity
           pipeline-transform   identity
           keep-alive?          true
           response-buffer-size 65536
           epoll?               false
           name-resolver        :default
           log-activity         :debug
           http-versions        [:http2 :http1]
           force-h2c?           false}
    :as   opts}]

  (let [host (.getHostName remote-address)
        port (.getPort remote-address)
        explicit-port? (and (pos? port) (not= port (if ssl? 443 80)))
        proxy-options' (when (some? proxy-options)
                         (assoc proxy-options :ssl? ssl?))
        non-tun-proxy? (non-tunnel-proxy? proxy-options')
        keep-alive?' (boolean (or keep-alive? (when (some? proxy-options)
                                                (get proxy-options :keep-alive? true))))
        authority (str host (when explicit-port? (str ":" port)))

        desired-protocols (mapv {:http1 ApplicationProtocolNames/HTTP_1_1
                                 :http2 ApplicationProtocolNames/HTTP_2}
                                http-versions)

        ssl-context (client-ssl-context ssl? ssl-context desired-protocols insecure?)

        logger (cond
                 (instance? LoggingHandler log-activity) log-activity
                 (some? log-activity) (netty/activity-logger "aleph-client" log-activity)
                 :else nil)

        pipeline-builder (make-pipeline-builder
                           (assoc opts
                                  :ssl? ssl?
                                  :ssl-context ssl-context
                                  :remote-address remote-address
                                  :raw-stream? raw-stream?
                                  :response-buffer-size response-buffer-size
                                  :logger logger
                                  :pipeline-transform pipeline-transform))

        ch (netty/create-client-chan
             {:pipeline-builder    pipeline-builder
              :bootstrap-transform bootstrap-transform
              :remote-address      remote-address
              :local-address       local-address
              :transport           (netty/determine-transport transport epoll?)
              :name-resolver       name-resolver})]

    (d/chain' ch
              (fn setup-client
                [^Channel ch]
                (log/debug "Channel:" ch)

                ;; We know the SSL handshake must be complete because create-client wraps the
                ;; future with maybe-ssl-handshake-future, so we can get the negotiated
                ;; protocol, falling back to HTTP/1.1 by default.
                (let [pipeline (.pipeline ch)
                      protocol (cond
                                 ssl?
                                 (or (-> pipeline
                                         ^SslHandler (.get ^Class SslHandler)
                                         (.applicationProtocol))
                                     ApplicationProtocolNames/HTTP_1_1) ; Not using ALPN, HTTP/2 isn't allowed

                                 force-h2c?
                                 (do
                                   (log/info "Forcing HTTP/2 over cleartext. Be sure to do this only with servers you control.")
                                   ApplicationProtocolNames/HTTP_2)

                                 :else
                                 ApplicationProtocolNames/HTTP_1_1) ; Not using SSL, HTTP/2 isn't allowed unless h2c requested
                      setup-opts (assoc opts
                                        :authority authority
                                        :ch ch
                                        :is-server? false
                                        :keep-alive? keep-alive?
                                        :keep-alive?' keep-alive?'
                                        :logger logger
                                        :non-tun-proxy? non-tun-proxy?
                                        :pipeline pipeline
                                        :pipeline-transform pipeline-transform
                                        :raw-stream? raw-stream?
                                        :remote-address remote-address
                                        :response-buffer-size response-buffer-size
                                        :ssl-context ssl-context
                                        :ssl? ssl?)]

                  (log/debug (str "Using HTTP protocol: " protocol)
                             {:authority authority
                              :ssl? ssl?
                              :force-h2c? force-h2c?})

                  ;; can't use ApnHandler, because we need to coordinate with Manifold code
                  (let [http-req-handler
                        (cond (.equals ApplicationProtocolNames/HTTP_1_1 protocol)
                              (setup-http1-client setup-opts)

                              (.equals ApplicationProtocolNames/HTTP_2 protocol)
                              (do
                                (http2/setup-conn-pipeline setup-opts)
                                (http2-req-handler setup-opts))

                              :else
                              (do
                                (let [msg (str "Unknown protocol: " protocol)
                                      e (IllegalStateException. msg)]
                                  (log/error e msg)
                                  (netty/close ch)
                                  (throw e))))]

                    ;; Both Netty and Aleph are set up, unpause the pipeline
                    (when (.get pipeline "pause-handler")
                      (log/debug "Unpausing pipeline")
                      (.remove pipeline "pause-handler"))

                    (fn http-req-fn
                      [req]
                      (log/trace "http-req-fn fired")
                      (log/debug "client request:" req)

                      ;; If :aleph/close is set in the req, closes the channel and
                      ;; returns a deferred containing the result.
                      (if (or (contains? req :aleph/close)
                              (contains? req ::close))
                        (-> ch (netty/close) (netty/wrap-future))

                        (let [t0 (System/nanoTime)
                              ;; I suspect the below is an error for http1
                              ;; since the shared handler might not match.
                              ;; Should work for HTTP2, though
                              raw-stream? (get req :raw-stream? raw-stream?)]

                          (if (or (not (.isActive ch))
                                  (not (.isOpen ch)))
                            (d/error-deferred
                              (ex-info "Channel is inactive/closed."
                                       {:req     req
                                        :ch      ch
                                        :open?   (.isOpen ch)
                                        :active? (.isActive ch)}))

                            (-> (http-req-handler req)
                                (d/chain' (rsp-handler
                                            {:ch                   ch
                                             :keep-alive?          keep-alive? ; why not keep-alive?'
                                             :raw-stream?          raw-stream?
                                             :req                  req
                                             :response-buffer-size response-buffer-size
                                             :t0                   t0})))))))))))))



(comment

  (do
    (def conn @(http-connection
                 (InetSocketAddress/createUnresolved "www.google.com" (int 443))
                 true
                 {:on-closed #(println "http conn closed")
                  :http-versions  [:http1]}))

    (conn {:request-method :get}))
  )
