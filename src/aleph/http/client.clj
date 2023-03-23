(ns ^:no-doc aleph.http.client
  (:require
    [aleph.http.core :as http]
    [aleph.http.multipart :as multipart]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    ;; Do not remove
    (aleph.utils
      ProxyConnectionTimeoutException)
    (java.io
      IOException)
    (java.net
      URI
      InetSocketAddress
      IDN
      URL)
    (io.netty.buffer
      ByteBuf)
    (io.netty.handler.codec.http
      DefaultHttpHeaders
      HttpClientCodec
      HttpRequest
      HttpResponse
      HttpContent
      HttpUtil
      HttpHeaderNames
      LastHttpContent
      FullHttpResponse)
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelHandlerContext
      ChannelPipeline)
    (io.netty.handler.codec
      TooLongFrameException)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (io.netty.handler.proxy
      ProxyConnectionEvent
      ProxyConnectException
      ProxyHandler
      HttpProxyHandler
      HttpProxyHandler$HttpProxyConnectException
      Socks4ProxyHandler
      Socks5ProxyHandler)
    (io.netty.handler.logging
      LoggingHandler)
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
  (let [^Throwable ex (http/decoder-failure msg)]
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
         (http/decoder-failed? msg)
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
         (http/decoder-failed? msg)
         (handle-decoder-failure ctx msg stream complete response-stream)

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
    {pipeline-transform      identity
     response-buffer-size    65536
     max-initial-line-length 65536
     max-header-size         65536
     max-chunk-size          65536
     idle-timeout            0}}]
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
           transport
           proxy-options]
    :or   {bootstrap-transform  identity
           keep-alive?          true
           response-buffer-size 65536
           epoll?               false
           name-resolver        :default}
    :as   options}]
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
            {:pipeline-builder    (pipeline-builder responses (assoc options :ssl? ssl?))
             :ssl-context         (when ssl?
                                    (or ssl-context
                                        (if insecure?
                                          (netty/insecure-ssl-client-context)
                                          (netty/ssl-client-context))))
             :bootstrap-transform bootstrap-transform
             :remote-address      remote-address
             :local-address       local-address
             :transport           (netty/determine-transport transport epoll?)
             :name-resolver       name-resolver})]
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

