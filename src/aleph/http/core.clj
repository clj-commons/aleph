(ns aleph.http.core
  (:require
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [clojure.set :as set]
    [clojure.string :as str]
    [clj-commons.byte-streams :as bs]
    [clj-commons.byte-streams.graph :as g]
    [potemkin :as p]
    [clojure.java.io :as io])
  (:import
    [io.netty.channel
     Channel
     DefaultFileRegion
     ChannelFuture
     ChannelFutureListener
     ChannelPipeline
     ChannelHandler
     ChannelHandlerContext]
    [io.netty.buffer
     ByteBuf]
    [java.nio
     ByteBuffer]
    [io.netty.handler.codec.http
     DefaultHttpRequest DefaultLastHttpContent
     DefaultHttpResponse DefaultFullHttpRequest
     FullHttpRequest
     HttpHeaders HttpUtil HttpContent
     HttpMethod HttpRequest HttpMessage
     HttpResponse HttpResponseStatus
     DefaultHttpContent
     HttpVersion
     LastHttpContent HttpChunkedInput]
    [io.netty.handler.timeout
     IdleState
     IdleStateEvent
     IdleStateHandler]
    [io.netty.handler.stream
     ChunkedInput
     ChunkedFile
     ChunkedWriteHandler]
    [io.netty.handler.codec.http.websocketx
     WebSocketFrame
     PingWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     CloseWebSocketFrame
     WebSocketChunkedInput]
    [java.io
     File
     RandomAccessFile
     Closeable]
    [java.nio.file Path]
    [java.nio.channels
     FileChannel
     FileChannel$MapMode]
    [java.util.concurrent
     ConcurrentHashMap
     ConcurrentLinkedQueue
     TimeUnit]
    [java.util.concurrent.atomic
     AtomicBoolean]))

(def non-standard-keys
  (let [ks ["Content-MD5"
            "ETag"
            "WWW-Authenticate"
            "X-XSS-Protection"
            "X-WebKit-CSP"
            "X-UA-Compatible"
            "X-ATT-DeviceId"
            "DNT"
            "P3P"
            "TE"]]
    (zipmap
      (map str/lower-case ks)
      (map #(HttpHeaders/newEntity %) ks))))

(def ^ConcurrentHashMap cached-header-keys (ConcurrentHashMap.))

(defn normalize-header-key
  "Normalizes a header key to `Ab-Cd` format."
  [s]
  (if-let [s' (.get cached-header-keys s)]
    s'
    (let [s' (str/lower-case (name s))
          s' (or
               (non-standard-keys s')
               (->> (str/split s' #"-")
                 (map str/capitalize)
                 (str/join "-")
                 HttpHeaders/newEntity))]

      ;; in practice this should never happen, so we
      ;; can be stupid about cache expiration
      (when (< 10000 (.size cached-header-keys))
        (.clear cached-header-keys))

      (.put cached-header-keys s s')
      s')))

(p/def-map-type HeaderMap
  [^HttpHeaders headers
   added
   removed
   mta]
  (meta [_]
    mta)
  (with-meta [_ m]
    (HeaderMap.
      headers
      added
      removed
      m))
  (keys [_]
    (set/difference
      (set/union
        (set (map str/lower-case (.names headers)))
        (set (keys added)))
      (set removed)))
  (assoc [_ k v]
    (HeaderMap.
      headers
      (assoc added k v)
      (disj removed k)
      mta))
  (dissoc [_ k]
    (HeaderMap.
      headers
      (dissoc added k)
      (conj (or removed #{}) k)
      mta))
  (get [_ k default-value]
    (if (contains? removed k)
      default-value
      (if-let [e (find added k)]
        (val e)
        (let [k' (str/lower-case (name k))
              vs (.getAll headers k')]
          (if (.isEmpty vs)
            default-value
            (if (== 1 (.size vs))
              (.get vs 0)
              (str/join "," vs))))))))

(defn headers->map [^HttpHeaders h]
  (HeaderMap. h nil nil nil))

(defn map->headers! [^HttpHeaders h m]
  (doseq [e m]
    (let [k (normalize-header-key (key e))
          v (val e)]

      (cond
        (nil? v)
        (throw (IllegalArgumentException. (str "nil value for header key '" k "'")))

        (sequential? v)
        (.add h ^CharSequence k ^Iterable v)

        :else
        (.add h ^CharSequence k ^Object v)))))

(defn ring-response->netty-response [m]
  (let [status (get m :status 200)
        headers (get m :headers)
        rsp (DefaultHttpResponse.
              HttpVersion/HTTP_1_1
              (HttpResponseStatus/valueOf status)
              false)]
    (when headers
      (map->headers! (.headers rsp) headers))
    rsp))

(defn ring-request->netty-request [m]
  (let [headers (get m :headers)
        req (DefaultHttpRequest.
              HttpVersion/HTTP_1_1
              (-> m (get :request-method) name str/upper-case HttpMethod/valueOf)
              (str (get m :uri)
                (when-let [q (get m :query-string)]
                  (str "?" q))))]
    (when headers
      (map->headers! (.headers req) headers))
    req))

(defn ring-request->full-netty-request [m]
  (let [headers (get m :headers)
        req (DefaultFullHttpRequest.
              HttpVersion/HTTP_1_1
              (-> m (get :request-method) name str/upper-case HttpMethod/valueOf)
              (str (get m :uri)
                (when-let [q (get m :query-string)]
                  (str "?" q)))
              (netty/to-byte-buf (:body m)))]
    (when headers
      (map->headers! (.headers req) headers))
    req))

(p/def-derived-map NettyRequest
  [^HttpRequest req
   ssl?
   ^Channel ch
   ^AtomicBoolean websocket?
   question-mark-index
   body]
  :uri (let [idx (long question-mark-index)]
         (if (neg? idx)
           (.getUri req)
           (.substring (.getUri req) 0 idx)))
  :query-string (let [uri (.uri req)]
                  (if (neg? question-mark-index)
                    nil
                    (.substring uri (unchecked-inc question-mark-index))))
  :headers (-> req .headers headers->map)
  :request-method (-> req .method .name str/lower-case keyword)
  :body body
  :scheme (if ssl? :https :http)
  :aleph/keep-alive? (HttpUtil/isKeepAlive req)
  :server-name (netty/channel-server-name ch)
  :server-port (netty/channel-server-port ch)
  :remote-addr (netty/channel-remote-address ch))

(p/def-derived-map NettyResponse [^HttpResponse rsp complete body]
  :status (-> rsp .status .code)
  :aleph/keep-alive? (HttpUtil/isKeepAlive rsp)
  :headers (-> rsp .headers headers->map)
  :aleph/complete complete
  :body body)

(defn netty-request->ring-request [^HttpRequest req ssl? ch body]
  (assoc
    (->NettyRequest
      req
      ssl?
      ch
      (AtomicBoolean. false)
      (-> req .uri (.indexOf (int 63))) body)
    :aleph/request-arrived (System/nanoTime)))

(defn netty-response->ring-response [rsp complete body]
  (->NettyResponse rsp complete body))

(defn ring-request-ssl-session [^NettyRequest req]
  (netty/channel-ssl-session (.ch req)))

;;;

(defn has-content-length? [^HttpMessage msg]
  (HttpUtil/isContentLengthSet msg))

(defn try-set-content-length! [^HttpMessage msg ^long length]
  (when-not (has-content-length? msg)
    (HttpUtil/setContentLength msg length)))

(def empty-last-content LastHttpContent/EMPTY_LAST_CONTENT)

(let [ary-class (class (byte-array 0))]
  (defn coerce-element [x]
    (if (or
          (instance? String x)
          (instance? ary-class x)
          (instance? ByteBuffer x)
          (instance? ByteBuf x))
      x
      (str x))))

(defn chunked-writer-enabled? [^Channel ch]
  (some? (-> ch netty/channel .pipeline (.get ChunkedWriteHandler))))

(defn send-streaming-body [ch ^HttpMessage msg body]

  (HttpUtil/setTransferEncodingChunked msg (boolean (not (has-content-length? msg))))
  (netty/write ch msg)

  (if-let [body' (if (sequential? body)

                   (let [buf (netty/allocate ch)
                         pending? (instance? clojure.lang.IPending body)]
                     (loop [s (map coerce-element body)]
                       (cond

                         (and pending? (not (realized? s)))
                         (do
                           (netty/write-and-flush ch buf)
                           s)

                         (empty? s)
                         (do
                           (netty/write-and-flush ch buf)
                           nil)

                         (or (not pending?) (realized? s))
                         (let [x (first s)]
                           (netty/append-to-buf! buf x)
                           (recur (rest s)))

                         :else
                         (do
                           (netty/write-and-flush ch buf)
                           s))))

                   (do
                     (netty/flush ch)
                     body))]

    (let [src (if (or (sequential? body') (s/stream? body'))
                (->> body'
                     s/->source
                     (s/map (fn [x]
                              (try
                                (netty/to-byte-buf x)
                                (catch Throwable e
                                  (log/error e "error converting " (.getName (class x)) " to ByteBuf")
                                  (netty/close ch))))))
                (netty/to-byte-buf-stream body' 8192))

          sink (netty/sink ch false #(DefaultHttpContent. %))

          ;; mustn't close over body' if NOT a stream, can hold on to data too long when conns are keep-alive
          ch-close-handler (if (s/stream? body')
                             #(s/close! body')
                             #(s/close! src))]

      (s/connect src sink)

      (-> ch
          netty/channel
          .closeFuture
          netty/wrap-future
          (d/chain' (fn [_] (ch-close-handler))))

      (let [d (d/deferred)]
        (s/on-closed sink
                     (fn []
                       (when (instance? Closeable body)
                         (.close ^Closeable body))

                       (.execute (-> ch aleph.netty/channel .eventLoop)
                                 #(d/success! d
                                              (netty/write-and-flush ch empty-last-content)))))
        d))

    (netty/write-and-flush ch empty-last-content)))

(def default-chunk-size 8192)

(deftype HttpFile [^File fd ^long offset ^long length ^long chunk-size])

(defmethod print-method HttpFile [^HttpFile file ^java.io.Writer w]
  (.write w (format "HttpFile[fd:%s offset:%s length:%s]"
                    (.-fd file)
                    (.-offset file)
                    (.-length file))))

(defn http-file
  ([path]
   (http-file path nil nil default-chunk-size))
  ([path offset length]
   (http-file path offset length default-chunk-size))
  ([path offset length chunk-size]
   (let [^File
         fd (cond
              (string? path)
              (io/file path)

              (instance? File path)
              path

              (instance? Path path)
              (.toFile ^Path path)

              :else
              (throw
               (IllegalArgumentException.
                (str "cannot conver " (class path) " to file, "
                     "expected either string, java.io.File "
                     "or java.nio.file.Path"))))
         region? (or (some? offset) (some? length))]
     (when-not (.exists fd)
       (throw
        (IllegalArgumentException.
         (str fd " file does not exist"))))

     (when (.isDirectory fd)
       (throw
        (IllegalArgumentException.
         (str fd " is a directory, file expected"))))

     (when (and region? (not (<= 0 offset)))
       (throw
        (IllegalArgumentException.
         "offset of the region should be 0 or greater")))

     (when (and region? (not (pos? length)))
       (throw
        (IllegalArgumentException.
         "length of the region should be greater than 0")))

     (let [len (.length fd)
           [p c] (if region?
                   [offset length]
                   [0 len])
           chunk-size (or chunk-size default-chunk-size)]
       (when (and region? (< len (+ offset length)))
         (throw
          (IllegalArgumentException.
           "the region exceeds the size of the file")))

       (HttpFile. fd p c chunk-size)))))

(bs/def-conversion ^{:cost 0} [HttpFile (bs/seq-of ByteBuffer)]
  [file {:keys [chunk-size writable?]
         :or {chunk-size (int default-chunk-size)
              writable? false}}]
  (let [^RandomAccessFile raf (RandomAccessFile. ^File (.-fd file)
                                                 (if writable? "rw" "r"))
        ^FileChannel fc (.getChannel raf)
        end-offset (+ (.-offset file) (.-length file))
        buf-seq (fn buf-seq [offset]
                  (when-not (<= end-offset offset)
                    (let [remaining (- end-offset offset)]
                      (lazy-seq
                       (cons
                        (.map fc
                              (if writable?
                                FileChannel$MapMode/READ_WRITE
                                FileChannel$MapMode/READ_ONLY)
                              offset
                              (min remaining chunk-size))
                        (buf-seq (+ offset chunk-size)))))))]
    (g/closeable-seq
     (buf-seq (.-offset file))
     false
     #(do
        (.close raf)
        (.close fc)))))

(defn send-chunked-file [ch ^HttpMessage msg ^HttpFile file]
  (let [raf (RandomAccessFile. ^File (.-fd file) "r")
        cf (ChunkedFile. raf
                         (.-offset file)
                         (.-length file)
                         (.-chunk-size file))]
    (try-set-content-length! msg (.-length file))
    (netty/write ch msg)
    (netty/write-and-flush ch (HttpChunkedInput. cf))))

(defn send-chunked-body [ch ^HttpMessage msg ^ChunkedInput body]
  (netty/write ch msg)
  (netty/write-and-flush ch body))

(defn send-file-region [ch ^HttpMessage msg ^HttpFile file]
  (let [raf (RandomAccessFile. ^File (.-fd file) "r")
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc (.-offset file) (.-length file))]
    (try-set-content-length! msg (.-length file))
    (netty/write ch msg)
    (netty/write ch fr)
    (netty/write-and-flush ch empty-last-content)))

(defn- file->stream [^HttpFile file]
  (-> file
      (bs/to-byte-buffers {:chunk-size (.-chunk-size file)})
      s/->source))

(defn send-file-body [ch ssl? ^HttpMessage msg ^HttpFile file]
  (cond
    ssl?
    (let [body (when (pos-int? (.length file)) (file->stream file))]
      (send-streaming-body ch msg body))

    (chunked-writer-enabled? ch)
    (send-chunked-file ch msg file)

    :else
    (send-file-region ch msg file)))

(defn send-contiguous-body [ch ^HttpMessage msg body]
  (let [omitted? (identical? :aleph/omitted body)
        body (if (or (nil? body) omitted?)
               empty-last-content
               (DefaultLastHttpContent. (netty/to-byte-buf ch body)))
        length (-> ^HttpContent body .content .readableBytes)]

    (when-not omitted?
      (if (instance? HttpResponse msg)
        (let [code (-> ^HttpResponse msg .status .code)]
          (when-not (or (<= 100 code 199) (= 204 code))
            (try-set-content-length! msg length)))
        (try-set-content-length! msg length)))

    (netty/write ch msg)
    (netty/write-and-flush ch body)))

(let [ary-class (class (byte-array 0))

      ;; extracted to make `send-message` more inlineable
      handle-cleanup
      (fn [ch f]
        (-> f
            (d/chain'
              (fn [^ChannelFuture f]
                (if f
                  (.addListener f ChannelFutureListener/CLOSE)
                  (netty/close ch))))
            (d/catch' (fn [_]))))]

  (defn send-message
    [ch keep-alive? ssl? ^HttpMessage msg body]

    (let [f (cond

              (or
                (nil? body)
                (identical? :aleph/omitted body)
                (instance? String body)
                (instance? ary-class body)
                (instance? ByteBuffer body)
                (instance? ByteBuf body))
              (send-contiguous-body ch msg body)

              (instance? ChunkedInput body)
              (send-chunked-body ch msg body)

              (instance? File body)
              (send-file-body ch ssl? msg (http-file body))

              (instance? Path body)
              (send-file-body ch ssl? msg (http-file body))

              (instance? HttpFile body)
              (send-file-body ch ssl? msg body)

              :else
              (let [class-name (.getName (class body))]
                (try
                  (send-streaming-body ch msg body)
                  (catch Throwable e
                    (log/error e "error sending body of type " class-name)))))]

      (when-not keep-alive?
        (handle-cleanup ch f))

      f)))

(deftype WebsocketPing [deferred payload])

(deftype WebsocketClose [deferred status-code reason-text])

(def close-empty-status-code -1)

(defn resolve-pings! [^ConcurrentLinkedQueue pending-pings v]
  (loop []
    (when-let [^WebsocketPing ping (.poll pending-pings)]
      (let [d' (.-deferred ping)]
        (when (not (realized? d'))
          (try
            (d/success! d' v)
            (catch Throwable e
              (log/error e "error in ping callback")))))
      (recur))))

(defn websocket-message-coerce-fn
  ([ch pending-pings]
   (websocket-message-coerce-fn ch pending-pings nil))
  ([^Channel ch ^ConcurrentLinkedQueue pending-pings close-handshake-fn]
   (fn [msg]
     (condp instance? msg
       WebSocketFrame
       msg

       ChunkedInput
       msg

       WebsocketPing
       (let [^WebsocketPing msg msg
             ;; this check should be safe as we rely on the strictly sequential
             ;; processing of all messages put onto the same stream
             send-ping? (.isEmpty pending-pings)]
         (.offer pending-pings msg)
         (when send-ping?
           (if-some [payload (.-payload msg)]
             (->> payload
                  (netty/to-byte-buf ch)
                  (PingWebSocketFrame.))
             (PingWebSocketFrame.))))

       WebsocketClose
       (when (some? close-handshake-fn)
         (let [^WebsocketClose msg msg
               code (.-status-code msg)
               frame (if (identical? close-empty-status-code code)
                       (CloseWebSocketFrame.)
                       (CloseWebSocketFrame. ^int code
                                             ^String (.-reason-text msg)))
               succeed? (close-handshake-fn frame)]
           ;; it still feels somewhat clumsy to make concurrent
           ;; updates and realized deferred from internals of the
           ;; function that meant to be a stateless coercer
           (when-not (d/realized? (.-deferred msg))
             (d/success! (.-deferred msg) succeed?))

           ;; we want to close the sink here to stop accepting
           ;; new messages from the user
           (when succeed?
             netty/sink-close-marker)))

       CharSequence
       (TextWebSocketFrame. (bs/to-string msg))

       (BinaryWebSocketFrame. (netty/to-byte-buf ch msg))))))

(defn close-on-idle-handler []
  (netty/channel-handler
   :user-event-triggered
   ([_ ctx evt]
    (if (and (instance? IdleStateEvent evt)
             (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
      (netty/close ctx)
      (.fireUserEventTriggered ctx evt)))))

(defn attach-idle-handlers [^ChannelPipeline pipeline idle-timeout]
  (if (pos? idle-timeout)
    (doto pipeline
      (.addLast "idle" ^ChannelHandler (IdleStateHandler. 0 0 idle-timeout TimeUnit/MILLISECONDS))
      (.addLast "idle-close" ^ChannelHandler (close-on-idle-handler)))
    pipeline))

(defn websocket-ping [conn d' data]
  (d/chain'
   (s/put! conn (aleph.http.core.WebsocketPing. d' data))
   #(when (and (false? %) (not (d/realized? d')))
      ;; meaning connection is already closed
      (d/success! d' false)))
  d')

(defn websocket-close! [conn status-code reason-text d']
  (when-not (or (identical? close-empty-status-code status-code)
                (<= 1000 status-code 4999))
    (throw (IllegalArgumentException.
            "websocket status code should be in range 1000-4999")))

  (let [payload (aleph.http.core/WebsocketClose. d' status-code reason-text)]
    (d/chain'
     (s/put! conn payload)
     (fn [put?]
       (when (and (false? put?) (not (d/realized? d')))
         ;; if the stream does not accept new messages,
         ;; connection is already closed
         (d/success! d' false))))
    d'))

(defn attach-heartbeats-handler [^ChannelPipeline pipeline heartbeats]
  (when (and (some? heartbeats)
             (pos? (:send-after-idle heartbeats)))
    (let [after (:send-after-idle heartbeats)]
      (.addLast pipeline
                "websocket-heartbeats"
                ^ChannelHandler
                (IdleStateHandler. 0 0 after TimeUnit/MILLISECONDS)))))

(defn handle-heartbeat [^ChannelHandlerContext ctx conn {:keys [payload
                                                                timeout]}]
  (let [done (d/deferred)]
    (websocket-ping conn done payload)
    (when (and timeout (pos? timeout))
      (-> done
          (d/timeout! timeout ::ping-timeout)
          (d/chain'
           (fn [v]
             (when (and (identical? ::ping-timeout v)
                        (.isOpen ^Channel (.channel ctx)))
               (netty/close ctx))))))))
