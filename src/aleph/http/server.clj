(ns aleph.http.server
  (:require
    [aleph.http.core :as http]
    [aleph.netty :as netty]
    [aleph.flow :as flow]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [clojure.string :as str]
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
     ByteBuf
     ByteBufHolder
     Unpooled]
    [io.netty.channel
     Channel
     ChannelHandlerContext
     ChannelHandler
     ChannelPipeline]
    [io.netty.handler.stream ChunkedWriteHandler]
    [io.netty.handler.timeout
     IdleState
     IdleStateEvent]
    [io.netty.handler.codec
     TooLongFrameException]
    [io.netty.handler.codec.http
     DefaultFullHttpResponse
     HttpContent HttpHeaders HttpUtil
     HttpContentCompressor
     HttpRequest HttpResponse
     HttpResponseStatus DefaultHttpHeaders
     HttpServerCodec HttpVersion HttpMethod
     LastHttpContent HttpServerExpectContinueHandler
     HttpHeaderNames]
    [io.netty.handler.codec.http.websocketx
     WebSocketServerHandshakerFactory
     WebSocketServerHandshaker
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     CloseWebSocketFrame
     WebSocketFrameAggregator]
    [io.netty.handler.codec.http.websocketx.extensions.compression
     WebSocketServerCompressionHandler]
    [java.io
     IOException]
    [java.net
     InetSocketAddress]
    [io.netty.util.concurrent
     FastThreadLocal]
    [java.util.concurrent
     TimeUnit
     Executor
     ExecutorService
     RejectedExecutionException
     ConcurrentLinkedQueue]
    [java.util.concurrent.atomic
     AtomicReference
     AtomicInteger
     AtomicBoolean]))

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
   :body (let [w (java.io.StringWriter.)]
           (.printStackTrace e (java.io.PrintWriter. w))
           (str w))})

(let [[server-name connection-name date-name]
      (map #(HttpHeaders/newEntity %) ["Server" "Connection" "Date"])

      [server-value keep-alive-value close-value]
      (map #(HttpHeaders/newEntity %) ["Aleph/0.4.7" "Keep-Alive" "Close"])]
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
        (let [headers (.headers rsp)]

          (when-not (.contains headers ^CharSequence server-name)
            (.set headers ^CharSequence server-name server-value))

          (when-not (.contains headers ^CharSequence date-name)
            (.set headers ^CharSequence date-name (date-header-value ctx)))

          (.set headers ^CharSequence connection-name (if keep-alive? keep-alive-value close-value))

          (http/send-message ctx keep-alive? ssl? rsp body))))))

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

          (instance? HttpRequest msg)
          (if (invalid-request? msg)
            (reject-invalid-request ctx msg)
            (let [req msg]
              (let [s (netty/buffered-source (netty/channel ctx) #(.readableBytes ^ByteBuf %) buffer-capacity)]
                (reset! stream s)
                (handle-request ctx req s))))

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
                         (.remove (.headers req) HttpHeaderNames/EXPECT)
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
     compression? false
     idle-timeout 0}}]
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-ring-handler ssl? handler rejected-handler executor request-buffer-size)
                    (ring-handler ssl? handler rejected-handler executor request-buffer-size))
          ^ChannelHandler
          continue-handler (if (nil? continue-handler)
                             (HttpServerExpectContinueHandler.)
                             (new-continue-handler continue-handler
                                                   continue-executor
                                                   ssl?))]

      (doto pipeline
        (.addLast "http-server"
          (HttpServerCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            false))
        (.addLast "continue-handler" continue-handler)
        (.addLast "request-handler" ^ChannelHandler handler)
        (#(when (or compression? (some? compression-level))
            (let [compressor (HttpContentCompressor. (or compression-level 6))]
              (.addAfter ^ChannelPipeline %1 "http-server" "deflater" compressor))
            (.addAfter ^ChannelPipeline %1 "deflater" "streamer" (ChunkedWriteHandler.))))
        (http/attach-idle-handlers idle-timeout)
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
           compression?
           continue-handler
           continue-executor]
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
      (pipeline-builder
        handler
        pipeline-transform
        (assoc options :executor executor :ssl? (or manual-ssl? (boolean ssl-context)) :continue-executor continue-executor'))
      ssl-context
      bootstrap-transform
      (when (and shutdown-executor? (or (instance? ExecutorService executor)
                                        (instance? ExecutorService continue-executor)))
        #(do
           (when (instance? ExecutorService executor)
             (.shutdown ^ExecutorService executor))
           (when (instance? ExecutorService continue-executor)
             (.shutdown ^ExecutorService continue-executor))))
      (if socket-address socket-address (InetSocketAddress. port))
      epoll?)))

;;;

(defn websocket-server-handler
  ([raw-stream? ch handshaker]
   (websocket-server-handler raw-stream? ch handshaker nil))
  ([raw-stream?
    ^Channel ch
    ^WebSocketServerHandshaker handshaker
    heartbeats]
   (let [d (d/deferred)
         ^ConcurrentLinkedQueue pending-pings (ConcurrentLinkedQueue.)
         closing? (AtomicBoolean. false)
         coerce-fn (http/websocket-message-coerce-fn
                    ch
                    pending-pings
                    (fn [^CloseWebSocketFrame frame]
                      (if-not (.compareAndSet closing? false true)
                        false
                        (do
                          (.close handshaker ch frame)
                          true))))
         description (fn [] {:websocket-selected-subprotocol (.selectedSubprotocol handshaker)})
         out (netty/sink ch false coerce-fn description)
         in (netty/buffered-source ch (constantly 1) 16)]

     (s/on-closed out #(http/resolve-pings! pending-pings false))

     (s/on-drained
      in
      ;; there's a chance that the connection was closed by the client,
      ;; in that case *out* would be closed earlier and the underlying
      ;; netty channel is already terminated
      #(when (and (.isOpen ch)
                  (.compareAndSet closing? false true))
         (.close handshaker ch (CloseWebSocketFrame.))))

     (let [s (doto
                 (s/splice out in)
               (reset-meta! {:aleph/channel ch}))]
       [s

        (netty/channel-inbound-handler

         :exception-caught
         ([_ ctx ex]
          (when-not (instance? IOException ex)
            (log/warn ex "error in websocket handler"))
          (s/close! out)
          (netty/close ctx))

         :channel-inactive
         ([_ ctx]
          (s/close! out)
          (s/close! in)
          (.fireChannelInactive ctx))

         :user-event-triggered
         ([_ ctx evt]
          (if (and (instance? IdleStateEvent evt)
                   (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
            (http/handle-heartbeat ctx s heartbeats)
            (.fireUserEventTriggered ctx evt)))

         :channel-read
         ([_ ctx msg]
         (let [ch (.channel ctx)]
           (cond
             (instance? TextWebSocketFrame msg)
             (if raw-stream?
               (let [body (.content ^TextWebSocketFrame msg)]
                ;; pass ByteBuf body directly to next level (it's
                ;; their reponsibility to release)
                 (netty/put! ch in body))
               (let [text (.text ^TextWebSocketFrame msg)]
                ;; working with text now, so we do not need
                ;; ByteBuf inside TextWebSocketFrame
                ;; note, that all *WebSocketFrame classes are
                ;; subclasses of DefaultByteBufHolder, meaning
                ;; there's no difference between releasing
                ;; frame & frame's content
                (netty/release msg)
                (netty/put! ch in text)))

             (instance? BinaryWebSocketFrame msg)
             (let [body (.content ^BinaryWebSocketFrame msg)]
               (netty/put! ch in
                 (if raw-stream?
                   body
                   ;; copied data into byte array, deallocating ByteBuf
                   (netty/release-buf->array body))))

             (instance? PingWebSocketFrame msg)
             (let [body (.content ^PingWebSocketFrame msg)]
               ;; reusing the same buffer
               ;; will be deallocated by Netty
               (netty/write-and-flush ch (PongWebSocketFrame. body)))

             (instance? PongWebSocketFrame msg)
             (do
               (netty/release msg)
               (http/resolve-pings! pending-pings true))

             (instance? CloseWebSocketFrame msg)
             (if-not (.compareAndSet closing? false true)
               ;; closing already, nothing else could be done
               (netty/release msg)
               ;; reusing the same buffer
               ;; will be deallocated by Netty
               (.close handshaker ch ^CloseWebSocketFrame msg))

             :else
             ;; no need to release buffer when passing to a next handler
             (.fireChannelRead ctx msg)))))]))))


;; note, as we set `keep-alive?` to `false`, `send-message` will close the connection
;; after writes are done, which is exactly what we expect to happen
(defn send-websocket-request-expected! [ch ssl?]
  (http/send-message
   ch
   false
   ssl?
   (http/ring-response->netty-response
    {:status 400
     :headers {"content-type" "text/plain"}})
   "expected websocket request"))

(defn websocket-upgrade-request?
  "Returns `true` if given request is an attempt to upgrade to websockets"
  [^NettyRequest req]
  (let [headers (:headers req)
        conn (get headers :connection)
        upgrade (get headers :upgrade)]
    (and (contains? (when (some? conn)
                      (set (map str/trim
                                (-> (str/lower-case conn) (str/split #",")))))
                    "upgrade")
         (= "websocket" (when (some? upgrade) (str/lower-case upgrade))))))

(defn initialize-websocket-handler
  [^NettyRequest req
   {:keys [raw-stream?
           headers
           max-frame-payload
           max-frame-size
           allow-extensions?
           compression?
           pipeline-transform
           heartbeats]
    :or {raw-stream? false
         max-frame-payload 65536
         max-frame-size 1048576
         allow-extensions? false
         compression? false}
    :as options}]

  (when (and (true? (:compression? options))
             (false? (:allow-extensions? options)))
    (throw (IllegalArgumentException.
            "Per-message deflate requires extensions to be allowed")))

  (-> req ^AtomicBoolean (.websocket?) (.set true))

  (let [^Channel ch (.ch req)
        ssl? (identical? :https (:scheme req))
        url (str
              (if ssl? "wss://" "ws://")
              (get-in req [:headers "host"])
              (:uri req))
        req (http/ring-request->full-netty-request req)
        factory (WebSocketServerHandshakerFactory.
                 url
                 nil
                 (or allow-extensions? compression?)
                 max-frame-payload)]
    (try
      (if-let [handshaker (.newHandshaker factory req)]
        (try
          (let [[s ^ChannelHandler handler] (websocket-server-handler raw-stream?
                                                                      ch
                                                                      handshaker
                                                                      heartbeats)
                p (.newPromise ch)
                h (doto (DefaultHttpHeaders.) (http/map->headers! headers))
                ^ChannelPipeline pipeline (.pipeline ch)]
            ;; actually, we're not going to except anything but websocket, so...
            (doto pipeline
              (.remove "request-handler")
              (.remove "continue-handler")
              (netty/remove-if-present HttpContentCompressor)
              (netty/remove-if-present ChunkedWriteHandler)
              (.addLast "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
              (http/attach-heartbeats-handler heartbeats)
              (.addLast "websocket-handler" handler))
            (when compression?
              ;; Hack:
              ;; WebSocketServerCompressionHandler is stateful and requires
              ;; HTTP request to be send through the pipeline
              ;; See more:
              ;; * https://github.com/clj-commons/aleph/issues/494
              ;; * https://github.com/netty/netty/pull/8973
              (let [compression-handler (WebSocketServerCompressionHandler.)
                    ctx (.context pipeline "websocket-frame-aggregator")]
                (.addAfter pipeline
                           "websocket-frame-aggregator"
                           "websocket-deflater"
                           compression-handler)
                (.fireChannelRead ctx req)))
            (-> (try
                  (netty/wrap-future (.handshake handshaker ch ^HttpRequest req h p))
                  (catch Throwable e
                    (d/error-deferred e)))
                (d/chain'
                 (fn [_]
                   (when (some? pipeline-transform)
                     (pipeline-transform (.pipeline ch)))
                   s))
                (d/catch'
                    (fn [e]
                      (send-websocket-request-expected! ch ssl?)
                      (d/error-deferred e)))))
          (catch Throwable e
            (d/error-deferred e)))
        (do
          (WebSocketServerHandshakerFactory/sendUnsupportedVersionResponse ch)
          (d/error-deferred (IllegalStateException. "unsupported version"))))
      (finally
        ;; I find this approach to handle request release somewhat
        ;; fragile... We have to release the object both in case of
        ;; handshake initialization and "unsupported version" response
        (netty/release req)))))
