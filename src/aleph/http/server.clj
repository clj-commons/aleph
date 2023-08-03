(ns ^:no-doc aleph.http.server
  (:require
    [aleph.flow :as flow]
    [aleph.http.common :as common]
    [aleph.http.core :as http]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (java.util
      EnumSet
      TimeZone
      Date
      Locale)
    (java.text
      DateFormat
      SimpleDateFormat)
    (io.aleph.dirigiste
      Stats$Metric)
    ;; Do not remove
    (aleph.http.core
      NettyRequest)
    (io.netty.buffer
      ByteBuf
      ByteBufHolder
      Unpooled)
    (io.netty.channel
      ChannelHandlerContext
      ChannelHandler
      ChannelPipeline)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (io.netty.handler.codec
      TooLongFrameException)
    (io.netty.handler.codec.http
      DefaultFullHttpResponse
      FullHttpRequest
      HttpContent HttpHeaders HttpUtil
      HttpContentCompressor HttpObjectAggregator
      HttpRequest HttpResponse
      HttpResponseStatus
      HttpServerCodec HttpVersion HttpMethod
      LastHttpContent HttpServerExpectContinueHandler
      HttpHeaderNames)
    (java.io
      IOException)
    (java.net
      InetSocketAddress)
    (io.netty.util.concurrent
      FastThreadLocal)
    (java.util.concurrent
      TimeUnit
      Executor
      ExecutorService
      RejectedExecutionException)
    (java.util.concurrent.atomic
      AtomicReference
      AtomicInteger
      AtomicBoolean)))

(set! *unchecked-math* true)

;;;

(defonce ^FastThreadLocal date-format (FastThreadLocal.))
(defonce ^FastThreadLocal date-value (FastThreadLocal.))

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
   :body "Internal Server Error"})

(let [[server-name connection-name date-name content-type]
      (map #(HttpHeaders/newEntity %) ["Server" "Connection" "Date" "Content-Type"])

      [server-value keep-alive-value close-value]
      (map #(HttpHeaders/newEntity %) ["Aleph/0.7.0-alpha1" "Keep-Alive" "Close"])]
  (defn send-response
    [^ChannelHandlerContext ctx keep-alive? ssl? error-handler rsp]
    (let [[^HttpResponse rsp body]
          (try
            [(http/ring-response->netty-response rsp)
             (get rsp :body)]
            (catch Throwable e
              (let [rsp (error-handler e)]
                [(http/ring-response->netty-response rsp)
                 (get rsp :body)])))]

      (netty/safe-execute ctx
                          (let [headers (.headers rsp)]

                            (when-not (.contains headers ^CharSequence server-name)
            (.set headers ^CharSequence server-name server-value))

                            (when-not (.contains headers ^CharSequence date-name)
            (.set headers ^CharSequence date-name (date-header-value ctx)))

                            (when (= (.get headers ^CharSequence content-type) "text/plain")
            (.set headers ^CharSequence content-type "text/plain; charset=UTF-8"))

                            (.set headers ^CharSequence connection-name (if keep-alive? keep-alive-value close-value))

                            (http/send-message ctx keep-alive? ssl? rsp body))))))

;;;

(defn invalid-value-exception [req x]
  (IllegalArgumentException.
   (str "Cannot treat "
        (pr-str x)
        (when (some? x) (str " of " (type x)))
        (format " as a response to '%s'.
Ring response expected.

Example: {:status 200
          :body \"hello world\"
          :headers \"text/plain\"}"
                (pr-str (select-keys req [:uri :request-method :query-string :headers]))))))

(defn handle-request
  "Converts to a Ring request, dispatches user handler on the appropriate
   executor if necessary, then sets up the chain to clean up, and convert
   the Ring response for netty"
  [^ChannelHandlerContext ctx
   ssl?
   handler
   rejected-handler
   error-handler
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
                        (error-handler e)))
                    {:status 503
                     :headers {"content-type" "text/plain"}
                     :body "503 Service Unavailable"})))

              ;; handle it inline (hope you know what you're doing)
              (try
                (handler req')
                (catch Throwable e
                  (error-handler e))))]

    (-> previous-response
        (d/chain'
         netty/wrap-future
         (fn [_]
           (netty/release req)
           (-> rsp
               (d/catch' error-handler)
               (d/chain'
                (fn [rsp]
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
                                     (error-handler (invalid-value-exception req rsp)))))))))))))

(defn exception-handler [ctx ex]
  (cond
    ;; do not need to log an entire stack trace when SSL handshake failed
    (netty/ssl-handshake-error? ex)
    (log/warn "SSL handshake failure:"
              (.getMessage ^Throwable (.getCause ^Throwable ex)))

    (not (instance? IOException ex))
    (log/warn ex "error in HTTP server")))

(defn invalid-request? [^HttpRequest req]
  (-> req .decoderResult .isFailure))

(defn- ^HttpResponseStatus cause->status [^Throwable cause]
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

(defn ring-handler
  [ssl? handler rejected-handler error-handler executor buffer-capacity]
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
              error-handler
              executor
              req
              @previous-response
              (when body (bs/to-input-stream body))
              (HttpHeaders/isKeepAlive req))))

        process-request
        (fn [ctx req]
          (if (HttpHeaders/isTransferEncodingChunked req)
            (let [s (netty/buffered-source (netty/channel ctx) #(alength ^bytes %) buffer-capacity)]
              (reset! stream s)
              (handle-request ctx req s))
            (reset! request req)))

        process-full-request
        (fn [ctx ^FullHttpRequest req]
          ;; HttpObjectAggregator disables chunked encoding, no need to check for it.
          (let [content (.content req)
                body (when (pos? (.readableBytes content))
                       (netty/buf->array content))]
            ;; Don't release content as it will happen automatically once whole
            ;; request is released.
            (handle-request ctx req body)))

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
          (.fireChannelRead ctx msg))))))

(defn raw-ring-handler
  [ssl? handler rejected-handler error-handler executor buffer-capacity]
  (let [buffer-capacity (long buffer-capacity)
        stream (atom nil)
        previous-response (atom nil)

        handle-raw-request
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
              (handle-raw-request ctx req s)))

          ;; A new request with no body has come in, start a new stream
          (instance? HttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (let [req msg]
              (let [s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)]
                (reset! stream s)
                (handle-raw-request ctx req s))))

          ;; More body content has arrived, put the bytes on the stream
          (instance? HttpContent msg)
          (let [content (.content ^HttpContent msg)]
            ;; content might empty most probably in case of EmptyLastHttpContent
            (when-not (zero? (.readableBytes content))
              (netty/put! (.channel ctx) @stream content))
            (when (instance? LastHttpContent msg)
              (s/close! @stream)))

          :else
          (.fireChannelRead ctx msg))))))

(def ^HttpResponse default-accept-response
  (DefaultFullHttpResponse. HttpVersion/HTTP_1_1
                            HttpResponseStatus/CONTINUE
                            Unpooled/EMPTY_BUFFER))

(HttpHeaders/setContentLength default-accept-response 0)

(def ^HttpResponse default-expectation-failed-response
  (DefaultFullHttpResponse. HttpVersion/HTTP_1_1
                            HttpResponseStatus/EXPECTATION_FAILED
                            Unpooled/EMPTY_BUFFER))

(HttpHeaders/setContentLength default-expectation-failed-response 0)

(defn new-continue-handler [continue-handler continue-executor ssl?]
  (netty/channel-inbound-handler

   :channel-read
   ([_ ctx msg]
    (if-not (and (instance? HttpRequest msg)
                 (HttpUtil/is100ContinueExpected ^HttpRequest msg))
      (.fireChannelRead ctx msg)
      (let [^HttpRequest req msg
            ch (.channel ctx)
            ring-req (http/netty-request->ring-request req ssl? ch nil)
            resume (fn [accept?]
                     (if (true? accept?)
                       ;; accepted
                       (let [rsp (.retainedDuplicate
                                       ^ByteBufHolder
                                       default-accept-response)]
                         (netty/write-and-flush ctx rsp)
                         (.fireChannelRead ctx req))
                       ;; rejected, use the default reject response if
                       ;; alternative is not provided
                       (do
                         (netty/release msg)
                         (if (false? accept?)
                           (let [rsp (.retainedDuplicate
                                      ^ByteBufHolder
                                      default-expectation-failed-response)]
                             (netty/write-and-flush ctx rsp))
                           (let [keep-alive? (HttpUtil/isKeepAlive req)
                                 rsp (http/ring-response->netty-response accept?)]
                             (http/send-message ctx keep-alive? ssl? rsp nil))))))]
        (if (nil? continue-executor)
          (resume (continue-handler ring-req))
          (d/chain'
           (d/future-with continue-executor (continue-handler ring-req))
           resume)))))))

(defn pipeline-builder
  "Returns a fn that adds all the needed ChannelHandlers to a ChannelPipeline"
  [handler
   pipeline-transform
   {:keys
    [executor
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
     idle-timeout
     continue-handler
     continue-executor]
    :or
    {request-buffer-size 16384
     max-initial-line-length 8192
     max-header-size 8192
     max-chunk-size 16384
     validate-headers false
     initial-buffer-size 128
     allow-duplicate-content-lengths false
     compression? false
     idle-timeout 0
     error-handler error-response}}]
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-ring-handler ssl? handler rejected-handler error-handler executor request-buffer-size)
                    (ring-handler ssl? handler rejected-handler error-handler executor request-buffer-size))
          ^ChannelHandler
          continue-handler (if (nil? continue-handler)
                             (HttpServerExpectContinueHandler.)
                             (new-continue-handler continue-handler
                                                   continue-executor
                                                   ssl?))]

      (doto pipeline
        (common/attach-idle-handlers idle-timeout)
        (.addLast "http-server"
          (HttpServerCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            validate-headers
            initial-buffer-size
            allow-duplicate-content-lengths))
        (#(when max-request-body-size
         (.addLast ^ChannelPipeline %1 "aggregator" (HttpObjectAggregator. max-request-body-size))))
        (.addLast "continue-handler" continue-handler)
        (.addLast "request-handler" ^ChannelHandler handler)
        (#(when (or compression? (some? compression-level))
            (let [compressor (HttpContentCompressor. (int (or compression-level 6)))]
              (.addAfter ^ChannelPipeline %1 "http-server" "deflater" compressor))
            (.addAfter ^ChannelPipeline %1 "deflater" "streamer" (ChunkedWriteHandler.))))
        pipeline-transform))))

;;;

(defn start-server
  [handler
   {:keys [port
           socket-address
           executor
           bootstrap-transform
           pipeline-transform
           ssl-context
           manual-ssl?
           shutdown-executor?
           epoll?
           transport
           compression?
           continue-handler
           continue-executor
           shutdown-timeout]
    :or {bootstrap-transform identity
         pipeline-transform identity
         shutdown-executor? true
         epoll? false
         compression? false
         shutdown-timeout netty/default-shutdown-timeout}
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
                     (str "invalid executor specification: " (pr-str executor)))))
        continue-executor' (cond
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
                                    (pr-str continue-executor)))))]
    (netty/start-server
     {:pipeline-builder (pipeline-builder
                         handler
                         pipeline-transform
                         (assoc options
                                :executor executor
                                :ssl? (or manual-ssl? (boolean ssl-context))
                                :continue-executor continue-executor'))
      :ssl-context ssl-context

      :bootstrap-transform bootstrap-transform
      :socket-address (if socket-address
                        socket-address
                        (InetSocketAddress. port))
      :on-close (when (and shutdown-executor? (or (instance? ExecutorService executor)
                                       (instance? ExecutorService continue-executor)))
                  #(do
                     (when (instance? ExecutorService executor)
                       (.shutdown ^ExecutorService executor))
                     (when (instance? ExecutorService continue-executor)
                       (.shutdown ^ExecutorService continue-executor))))
      :transport (netty/determine-transport transport epoll?)
      :shutdown-timeout shutdown-timeout})))
