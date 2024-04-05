(ns ^:no-doc aleph.http.server
  (:require
    [aleph.flow :as flow]
    [aleph.http.common :as common]
    [aleph.http.compression :as compress]
    [aleph.http.core :as http1]
    [aleph.http.http2 :as http2]
    [aleph.http.websocket.server :as ws.server]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.http ApnHandler)
    (aleph.http.core
      NettyRequest)
    (io.aleph.dirigiste
      Stats$Metric)
    (io.netty.buffer
      ByteBuf
      ByteBufHolder
      Unpooled)
    (io.netty.channel
      ChannelHandler
      ChannelHandlerContext
      ChannelPipeline)
    (io.netty.handler.codec
      TooLongFrameException)
    ;; Do not remove
    (io.netty.handler.codec.http
      DefaultFullHttpResponse
      FullHttpRequest
      HttpContent
      HttpContentCompressor
      HttpHeaderNames
      HttpMethod
      HttpObjectAggregator
      HttpRequest
      HttpResponse
      HttpResponseStatus
      HttpServerCodec
      HttpServerExpectContinueHandler
      HttpUtil
      HttpVersion
      LastHttpContent)
    (io.netty.handler.ssl
      ApplicationProtocolNames
      SslContext)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (io.netty.util AsciiString)
    (io.netty.util.concurrent
      FastThreadLocal)
    (java.io
      IOException)
    (java.net
      InetSocketAddress)
    (java.text
      SimpleDateFormat)
    (java.util
      EnumSet
      Locale
      TimeZone)
    (java.util.concurrent
      Executor
      ExecutorService
      RejectedExecutionException)
    (java.util.concurrent.atomic
      AtomicBoolean
      AtomicInteger)))

(set! *unchecked-math* true)

(defn ^:deprecated ^:no-doc error-response [^Throwable e]
  (log/error e "error in HTTP handler")
  {:status 500
   :headers {"content-type" "text/plain"}
   :body "Internal Server Error"})

(def apn-fallback-protocol ApplicationProtocolNames/HTTP_1_1)

(let [[server-name
       connection-name
       date-name
       content-type
       keep-alive-value
       close-value]
      (map #(AsciiString. ^CharSequence %)
           ; http1 - not lower-cased
           ["Server"
            "Connection"
            "Date"
            "Content-Type"
            "Keep-Alive"
            "Close"])]

  ;; http1
  (defn send-response
    "Converts the Ring response to a Netty HttpResponse, and then sends it to
     Netty to be sent out over the wire."
    ([^ChannelHandlerContext ctx keep-alive? ssl? rsp]
     (send-response ctx keep-alive? ssl? common/ring-error-response rsp))
    ([^ChannelHandlerContext ctx keep-alive? ssl? error-handler rsp]
     (let [[^HttpResponse rsp body]
           (try
             [(http1/ring-response->netty-response rsp)
              (get rsp :body)]

             (catch Throwable e
               (log/debug "Falling back to error-handler...")
               (let [rsp (error-handler e)]
                 [(http1/ring-response->netty-response rsp)
                  (get rsp :body)])))]

       (netty/safe-execute ctx
         (let [headers (.headers rsp)]
           (when-not (.contains headers ^CharSequence server-name)
             (.set headers ^CharSequence server-name common/aleph-server-header))

           (when-not (.contains headers ^CharSequence date-name)
             (.set headers ^CharSequence date-name (common/date-header-value (.executor ctx))))

           (when (= (.get headers ^CharSequence content-type) "text/plain")
             (.set headers ^CharSequence content-type "text/plain; charset=UTF-8"))

           ; http1
           (.set headers ^CharSequence connection-name (if keep-alive? keep-alive-value close-value))

           (http1/send-message ctx keep-alive? ssl? rsp body)))))))

; http1
(defn handle-request
  "Converts to a Ring request, dispatches user handler on the appropriate
   executor if necessary, then sets up the chain to clean up, and convert
   the Ring response for netty"
  ^:deprecated
  ([^ChannelHandlerContext ctx
    ssl?
    handler
    rejected-handler
    executor
    ^HttpRequest req
    previous-response
    body
    keep-alive?]
   (handle-request ctx ssl? handler rejected-handler common/ring-error-response executor req previous-response body keep-alive?))
  ([^ChannelHandlerContext ctx
    ssl?
    handler
    rejected-handler
    error-handler
    executor
    ^HttpRequest req                                        ; HTTP1
    previous-response
    body
    keep-alive?]
   (let [^NettyRequest req' (http1/netty-request->ring-request req ssl? (.channel ctx) body)
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
                         (error-handler e)))
                     {:status  503
                      :headers {"content-type" "text/plain"}
                      :body    "503 Service Unavailable"})))

               ;; else handle it inline (hope you know what you're doing)
               (try
                 (handler req')
                 (catch Throwable e
                   (error-handler e))))]

     ;; HTTP1
     ;; don't process the current response until the previous one is realized
     (d/chain' previous-response
               netty/wrap-future
               (fn [_]
                 (netty/release req)
                 (-> rsp
                     (d/catch' error-handler)
                     (d/chain'
                       (fn send-http-response [rsp]
                         (when (not (-> req' ^AtomicBoolean (.websocket?) .get))
                           (send-response ctx keep-alive? ssl? error-handler
                                          (cond

                                            (map? rsp)
                                            (if head?
                                              (assoc rsp :body :aleph/omitted)
                                              rsp)

                                            (nil? rsp)
                                            {:status 204}

                                            :else
                                            (error-handler (common/invalid-value-exception req rsp)))))))))))))

(defn exception-handler [ctx ex]
  (cond
    ;; do not need to log an entire stack trace when SSL handshake failed
    (netty/ssl-handshake-error? ex)
    (log/warn "SSL handshake failure:"
              (.getMessage ^Throwable (.getCause ^Throwable ex)))

    (not (instance? IOException ex))
    (log/warn ex "error in HTTP server")))

;; HTTP1
(defn invalid-request? [^HttpRequest req]
  (-> req .decoderResult .isFailure))

(defn- cause->status
  "Given an exception/throwable, tries to pick an appropriate HTTP status code.
   Defaults to 400."
  ^HttpResponseStatus
  [^Throwable cause]
  (if (instance? TooLongFrameException cause)
    (let [message (.getMessage cause)]
      (cond
        (.startsWith message "An HTTP line is larger than")
        HttpResponseStatus/REQUEST_URI_TOO_LONG

        (.startsWith message "HTTP header is larger than")
        HttpResponseStatus/REQUEST_HEADER_FIELDS_TOO_LARGE

        :else
        HttpResponseStatus/BAD_REQUEST))
    HttpResponseStatus/BAD_REQUEST))

;; HTTP1
(defn reject-invalid-request [ctx ^HttpRequest req]
  (let [cause (-> req .decoderResult .cause)
        status (cause->status cause)]
    (d/chain
      (netty/write-and-flush ctx
                             (DefaultFullHttpResponse.
                               HttpVersion/HTTP_1_1
                               status
                               (-> cause .getMessage netty/to-byte-buf)))
      netty/wrap-future
      (fn [_] (netty/close ctx)))))

;; http1
(defn ring-handler
  "Does not handle Ring maps, but rather, creates them for the user-supplied
   handler.

   Keeps track of the state of the HTTP/1 connection and its incoming objects,
   and dispatches to the user handler. Builds a Ring map from HttpRequest and
   FullHttpRequest, and converts incoming HttpContents to an InputStream for
   the body."
  ^:deprecated
  ([ssl? handler rejected-handler executor buffer-capacity]
   (ring-handler ssl? handler rejected-handler common/ring-error-response executor buffer-capacity))
  ([ssl? handler rejected-handler error-handler executor buffer-capacity]
   (let [buffer-capacity (long buffer-capacity)
         request (atom nil)
         buffer (atom [])
         buffer-size (AtomicInteger. 0)
         stream (atom nil)
         previous-response (atom nil)

         handle-req*
         (fn [^ChannelHandlerContext ctx req body]
           (reset! previous-response
                   (handle-request
                     ctx
                     ssl?
                     handler
                     rejected-handler
                     error-handler
                     executor
                     req
                     @previous-response
                     (when body (bs/to-input-stream body))
                     (HttpUtil/isKeepAlive req))))

         process-request
         (fn [ctx req]
           (if (HttpUtil/isTransferEncodingChunked req)
             (let [s (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)]
               (reset! stream s)
               (handle-req* ctx req s))
             (reset! request req)))

         process-full-request
         (fn [ctx ^FullHttpRequest req]
           ;; HttpObjectAggregator disables chunked encoding, no need to check for it.
           (let [content (.content req)
                 body (when (pos? (.readableBytes content))
                        (netty/buf->array content))]
             ;; Don't release content as it will happen automatically once whole
             ;; request is released.
             (handle-req* ctx req body)))

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
                   (handle-req* ctx @request nil))

                 (let [bufs (conj @buffer content)
                       bytes (netty/bufs->array bufs)]
                   (doseq [b bufs]
                     (netty/release b))
                   (handle-req* ctx @request bytes))))

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

                       (handle-req* ctx @request s))))))))]

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
        (exception-handler ctx ex))

       :channel-inactive
       ([_ ctx]
        (when-let [s @stream]
          (s/close! s))
        (doseq [b @buffer]
          (netty/release b))
        (.fireChannelInactive ctx))

       :channel-read
       ([_ ctx msg]
        (cond

          ;; Happens when io.netty.handler.codec.http.HttpObjectAggregator is part of the pipeline.
          (instance? FullHttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (process-full-request ctx msg))

          (instance? HttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (process-request ctx msg))

          (instance? HttpContent msg)
          (if (instance? LastHttpContent msg)
            (process-last-content ctx msg)
            (process-content ctx msg))

          :else
          (.fireChannelRead ctx msg)))))))

;; HTTP1
(defn raw-ring-handler
  "Like `ring-handler`, but the body is a Manifold stream of ByteBufs that
   the user must manually `release`."
  ^:deprecated
  ([ssl? handler rejected-handler executor buffer-capacity]
   (raw-ring-handler ssl? handler rejected-handler common/ring-error-response executor buffer-capacity))
  ([ssl? handler rejected-handler error-handler executor buffer-capacity]
   (let [buffer-capacity (long buffer-capacity)
         stream (atom nil)
         previous-response (atom nil)

         handle-req*
         (fn [^ChannelHandlerContext ctx req body]
           (reset! previous-response
                   (handle-request
                     ctx
                     ssl?
                     handler
                     rejected-handler
                     error-handler
                     executor
                     req
                     @previous-response
                     body
                     (HttpUtil/isKeepAlive req))))]

     (netty/channel-inbound-handler
       :exception-caught
       ([_ ctx ex]
        (exception-handler ctx ex))

       :channel-inactive
       ([_ ctx]
        (when-let [s @stream]
          (s/close! s))
        (.fireChannelInactive ctx))

       :channel-read
       ([_ ctx msg]
        (cond

          ;; Happens when io.netty.handler.codec.http.HttpObjectAggregator is part of the pipeline.
          (instance? FullHttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (let [^FullHttpRequest req msg
                  content (.content req)
                  ch (netty/channel ctx)
                  s (netty/source ch)]
              (when-not (zero? (.readableBytes content))
                ;; Retain the content of FullHttpRequest one extra time to
                ;; compensate for it being released together with the request.
                (netty/put! ch s (netty/acquire content)))
              (s/close! s)
              (handle-req* ctx req s)))

          ;; A new request with no body has come in, start a new stream
          (instance? HttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (let [req msg
                  s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)]
              (reset! stream s)
              (handle-req* ctx req s)))

          ;; More body content has arrived, put the bytes on the stream
          (instance? HttpContent msg)
          (let [content (.content ^HttpContent msg)]
            ;; content might empty most probably in case of EmptyLastHttpContent
            (when-not (zero? (.readableBytes content))
              (netty/put! (.channel ctx) @stream content))
            (when (instance? LastHttpContent msg)
              (s/close! @stream)))

          :else
          (.fireChannelRead ctx msg)))))))

;; HTTP1
(def ^HttpResponse default-accept-response
  (doto (DefaultFullHttpResponse. HttpVersion/HTTP_1_1
                                  HttpResponseStatus/CONTINUE
                                  Unpooled/EMPTY_BUFFER)
        (HttpUtil/setContentLength 0)))

;; HTTP1
(def ^HttpResponse default-expectation-failed-response
  (doto (DefaultFullHttpResponse. HttpVersion/HTTP_1_1
                                  HttpResponseStatus/EXPECTATION_FAILED
                                  Unpooled/EMPTY_BUFFER)
        (HttpUtil/setContentLength 0)))

;; HTTP1 - doesn't seem to be equivalent code in http2 netty code
(defn new-continue-handler
  "Wraps the supplied `continue-handler` that will respond to requests with the
   header \"expect: 100-continue\" set.

   `continue-handler` receives the ring request, and returns either a boolean
   or a custom ring response map. If boolean, it indicates whether the request
   is accepted or not, and in both cases, a default response is sent.

   If the `continue-executor` is nil, calls the handler on the Netty event loop
   thread. Otherwise, calls the handler on the supplied executor."
  [continue-handler continue-executor ssl?]
  (netty/channel-inbound-handler

    :channel-read
    ([_ ctx msg]
     (if-not (and (instance? HttpRequest msg)
                  (HttpUtil/is100ContinueExpected ^HttpRequest msg))
       (.fireChannelRead ctx msg)
       (let [^HttpRequest req msg
             ch (.channel ctx)
             ring-req (http1/netty-request->ring-request req ssl? ch nil)
             resume (fn continue-handler-accept
                      [accept?]
                      (if (true? accept?)
                        ;; accepted, send a 100 Continue response, and re-send
                        ;; req along Netty pipeline
                        (let [resp (.retainedDuplicate
                                    ^ByteBufHolder
                                    default-accept-response)]
                          (netty/write-and-flush ctx resp)
                          (.remove (.headers req) HttpHeaderNames/EXPECT)
                          (.fireChannelRead ctx req))

                        ;; rejected, use the default reject response if
                        ;; alternative is not provided
                        (do
                          (netty/release msg)
                          (if (false? accept?)
                            (let [resp (.retainedDuplicate
                                        ^ByteBufHolder
                                        default-expectation-failed-response)]
                              (netty/write-and-flush ctx resp))
                            (let [keep-alive? (HttpUtil/isKeepAlive req)
                                  resp (http1/ring-response->netty-response accept?)]
                              (http1/send-message ctx keep-alive? ssl? resp nil))))))]
         (if (nil? continue-executor)
           (resume (continue-handler ring-req))
           (d/chain'
             (d/future-with continue-executor (continue-handler ring-req))
             resume)))))))

(defn setup-http1-pipeline
  "Returns a fn that adds all the needed ChannelHandlers to a ChannelPipeline"
  [{:keys
    [^ChannelPipeline pipeline
     handler
     executor
     rejected-handler
     error-handler
     request-buffer-size
     max-request-body-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     validate-headers
     initial-buffer-size
     allow-duplicate-content-lengths
     raw-stream?
     ssl?
     compression?
     compression-level
     compression-options
     idle-timeout
     continue-handler
     continue-executor
     http1-pipeline-transform]
    :or
    {request-buffer-size             16384
     max-initial-line-length         8192
     max-header-size                 8192
     max-chunk-size                  16384
     validate-headers                false
     initial-buffer-size             128
     allow-duplicate-content-lengths false
     compression?                    false
     compression-options             compress/available-compressor-options
     idle-timeout                    0
     error-handler                   common/ring-error-response
     http1-pipeline-transform        identity}}]
  (log/trace "setup-http1-pipeline")
  (let [handler (if raw-stream?
                  (raw-ring-handler ssl? handler rejected-handler error-handler executor request-buffer-size)
                  (ring-handler ssl? handler rejected-handler error-handler executor request-buffer-size))
        ^ChannelHandler
        continue-handler (if (nil? continue-handler)
                           (HttpServerExpectContinueHandler.)
                           (new-continue-handler continue-handler
                                                 continue-executor
                                                 ssl?))
        compression? (or compression? (some? compression-level))]
    (doto pipeline
      (netty/add-idle-handlers idle-timeout)
      (.addLast "http-server"
                (HttpServerCodec.
                  max-initial-line-length
                  max-header-size
                      max-chunk-size
                      validate-headers
                      initial-buffer-size
                      allow-duplicate-content-lengths))
          ;; FIXME: HttpObjectAggregator and continue handler shouldn't really be mixed
          ;; since the former may send its own 100-continue response. In theory,
          ;; since whichever sends the 100-continue, removes the expect header,
          ;; it shouldn't be a problem, but we may need to rearrange this for the S3
          ;; signature case where we need to preserve the expect header.
          (#(when max-request-body-size
              (.addLast ^ChannelPipeline %1 "aggregator" (HttpObjectAggregator. max-request-body-size))))
          (.addLast "continue-handler" continue-handler)
          (.addLast "request-handler" ^ChannelHandler handler)

          ;; HTTP1 - HTTP2 code uses decorating coders/decoders
          (#(when compression?
              (let [compressor (if compression-level
                                 (HttpContentCompressor. (int compression-level))
                                 (HttpContentCompressor. ^"[Lio.netty.handler.codec.compression.CompressionOptions;" compression-options))]
                (.addAfter ^ChannelPipeline %1 "http-server" "deflater" compressor))
              (.addAfter ^ChannelPipeline %1 "deflater" "streamer" (ChunkedWriteHandler.))))
          http1-pipeline-transform)

    (log/debug "http1 server pipeline" pipeline)

    pipeline))

(defn ^:deprecated ^:no-doc pipeline-builder
  [handler pipeline-transform opts]
  #(setup-http1-pipeline (assoc opts
                                :pipeline %
                                :handler handler
                                :http1-pipeline-transform pipeline-transform)))

(defn make-pipeline-builder
  "Returns a function that initializes a new server channel's pipeline."
  [handler {:keys [ssl? ^SslContext ssl-context use-h2c?] :as opts}]
  (fn pipeline-builder*
    [^ChannelPipeline pipeline]
    (log/trace "pipeline-builder*" pipeline opts)
    (let [setup-opts (assoc opts
                            :handler handler
                            :server? true
                            :pipeline pipeline)]
      (cond ssl?
            (let [ssl-handler (netty/ssl-handler (.channel pipeline) ssl-context)]
              (log/info "Setting up secure HTTP server pipeline.")
              (log/info "ALPN HTTP versions:" (mapv str (.nextProtocols ssl-context)))

              (-> pipeline
                  (.addLast "ssl-handler" ssl-handler)
                  (.addLast "apn-handler"
                            (ApnHandler.
                              (fn setup-secure-pipeline
                                [^ChannelPipeline pipeline protocol]
                                (log/trace "setup-secure-pipeline: chosen protocol:" protocol)
                                (when (nil? (.applicationProtocol ssl-handler))
                                  (log/debug (str "ALPN not used. Protocol " protocol " chosen by fallback.")))
                                (cond (.equals ApplicationProtocolNames/HTTP_1_1 protocol)
                                      (setup-http1-pipeline setup-opts)

                                      (.equals ApplicationProtocolNames/HTTP_2 protocol)
                                      (http2/setup-conn-pipeline setup-opts)

                                      :else
                                      (let [msg (str "Unknown protocol: " protocol)
                                            e (IllegalStateException. msg)]
                                        (log/error e msg)
                                        (throw e))))
                              apn-fallback-protocol)))
              pipeline)

            use-h2c?
            (do
              (log/debug "Setting up cleartext HTTP/2 server pipeline.")
              (http2/setup-conn-pipeline setup-opts))

            :else
            (do
              (log/debug "Setting up insecure HTTP/1 server pipeline.")
              (setup-http1-pipeline setup-opts))))))

;;;

(defn- ^:no-doc setup-executor
  "Returns a general executor for user handlers to run on."
  [executor]
  (cond
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
        (str "invalid executor specification: " (pr-str executor))))))

(defn- ^:no-doc setup-continue-executor
  "Returns an executor for custom continue handlers to run on.

   Defaults to general Aleph server executor."
  [executor continue-executor]
  (cond
    (nil? continue-executor)
    executor

    (identical? :none continue-executor)
    nil

    (instance? Executor continue-executor)
    continue-executor

    :else
    (throw
      (IllegalArgumentException.
        (str "invalid continue-executor specification: "
             (pr-str continue-executor))))))


(defn ^:no-doc start-server
  [handler
   {:keys [port
           socket-address
           executor
           http-versions
           ssl-context
           manual-ssl?
           use-h2c?
           shutdown-executor?
           epoll?
           transport
           continue-executor
           shutdown-timeout
           bootstrap-transform]
    :or   {bootstrap-transform             identity
           shutdown-executor?              true
           epoll?                          false
           shutdown-timeout                netty/default-shutdown-timeout}
    :as   opts}]
  (let [^SslContext ssl-context (-> ssl-context
                                    (common/ensure-consistent-alpn-config http-versions)
                                    (netty/coerce-ssl-server-context))
        opts (assoc opts :ssl-context ssl-context)
        http1-pipeline-transform (common/validate-http1-pipeline-transform opts)
        executor (setup-executor executor)
        continue-executor (setup-continue-executor executor continue-executor)
        pipeline-builder (make-pipeline-builder
                           handler
                           (assoc opts
                                  :executor executor
                                  :ssl? (or manual-ssl? (boolean ssl-context))
                                  :http1-pipeline-transform http1-pipeline-transform
                                  :continue-executor continue-executor))]

    (if (some #{:http2} http-versions)
      (when (and (not ssl-context)
                 (not use-h2c?))
        (throw (IllegalArgumentException. "HTTP/2 requires ssl-context to be given or use-h2c? to be true.")))
      (when use-h2c?
        (throw (IllegalArgumentException. "use-h2c? may only be true when HTTP/2 is enabled."))))

    (when (and ssl-context
               use-h2c?)
      (throw (IllegalArgumentException. "use-h2c? must not be true when ssl-context is given.")))

    (netty/start-server
      {:pipeline-builder    pipeline-builder
       :bootstrap-transform bootstrap-transform
       :socket-address      (if socket-address
                              socket-address
                              (InetSocketAddress. port))
       :on-close            (when (and shutdown-executor?
                                       (or (instance? ExecutorService executor)
                                           (instance? ExecutorService continue-executor)))
                              #(do
                                 (when (instance? ExecutorService executor)
                                   (.shutdown ^ExecutorService executor))
                                 (when (and (instance? ExecutorService continue-executor)
                                            (not= continue-executor executor))
                                   (.shutdown ^ExecutorService continue-executor))))
       :transport           (netty/determine-transport transport epoll?)
       :shutdown-timeout    shutdown-timeout})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; backwards compatibility
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ^:deprecated ^:no-doc date-header-value
  [^ChannelHandlerContext ctx]
  (common/date-header-value (.executor ctx)))

(def ^:deprecated ^:no-doc date-value common/date-value)

(defonce ^:deprecated ^:no-doc ^FastThreadLocal
  date-format (doto (FastThreadLocal.)
                    (.set (doto (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss z" Locale/ENGLISH)
                                (.setTimeZone (TimeZone/getTimeZone "GMT"))))))

(def ^:deprecated ^:no-doc initialize-websocket-handler ws.server/initialize-websocket-handler)

(defn ^:deprecated ^:no-doc invalid-value-response
  [req x]
  (error-response (common/invalid-value-exception req x)))

(def ^:deprecated ^:no-doc rfc-1123-date-string common/rfc-1123-date-string)

(def ^:deprecated ^:no-doc send-websocket-request-expected! ws.server/send-websocket-request-expected!)
(def ^:deprecated ^:no-doc websocket-server-handler ws.server/websocket-server-handler)
(def ^:deprecated ^:no-doc websocket-upgrade-request? ws.server/websocket-upgrade-request?)




(comment

  (do
    (require '[aleph.http.client])
    (import '(io.netty.handler.ssl ApplicationProtocolConfig
                                   ApplicationProtocolConfig$Protocol
                                   ApplicationProtocolConfig$SelectorFailureBehavior
                                   ApplicationProtocolConfig$SelectedListenerFailureBehavior)))

  (def port "'alef' in ascii" 11256)

  ;; from examples/
  (defn hello-world-handler
    "A basic Ring handler which immediately returns 'hello world'"
    [req]
    (log/trace "hello-world-handler" req)
    {:status  200
     :headers {"content-type" "text/plain"}
     :body    "hello world!"})

  (def http2-ssl-ctx
    #_(netty/self-signed-ssl-context)

    ;; this creates a certificate from the local files aleph.localhost+3-key.pem and aleph.localhost+3.pem
    (netty/ssl-server-context
      {:private-key       "aleph.localhost+3-key.pem"
       :certificate-chain "aleph.localhost+3.pem"
       ;;:ssl-provider      :jdk
       ;;:ssl-provider      :openssl
       :ssl-provider      :openssl-refcnt
       :application-protocol-config
       (ApplicationProtocolConfig.
         ApplicationProtocolConfig$Protocol/ALPN
         ;; NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
         ApplicationProtocolConfig$SelectorFailureBehavior/NO_ADVERTISE
         ;; ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
         ApplicationProtocolConfig$SelectedListenerFailureBehavior/ACCEPT
         ^"[Ljava.lang.String;"
         (into-array String [ApplicationProtocolNames/HTTP_2]))}))


  ;; open and close
  #_(with-open [s (start-server hello-world-handler
                              {:port        port
                               :ssl-context http2-ssl-ctx})]
    (Thread/sleep 500)

    ;; basic test
    (do
      (def conn @(aleph.http.client/http-connection
                   (InetSocketAddress. "127.0.0.1" (int port))
                   true
                   {:on-closed     #(log/debug "http conn closed")
                    :http-versions [:http2]}))

      (def result @(d/timeout! (conn {:request-method :get})
                               2000
                               :timeout))))

  ;; restart server
  (do
    (declare s)
    (when (and (bound? #'s) s)
      (.close ^java.io.Closeable s))
    (def s (start-server hello-world-handler
                         {:port        port
                          :ssl-context http2-ssl-ctx
                          :logger      (netty/activity-logger "aleph-server" :debug)})))

  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "aleph.localhost" (int port))
                 true
                 {:on-closed     #(log/debug "http conn closed")
                  :http-versions [:http2]
                  :insecure? true}))

    (def result @(d/timeout! (conn {:request-method :get})
                             2000
                             :timeout))
    result)



  )
