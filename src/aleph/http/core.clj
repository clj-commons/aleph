(ns aleph.http.core
  (:require
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [clojure.set :as set]
    [clojure.string :as str]
    [byte-streams :as bs]
    [potemkin :as p])
  (:import
    [io.netty.channel
     Channel
     DefaultFileRegion
     ChannelFuture
     ChannelFutureListener
     ChannelHandlerContext]
    [io.netty.buffer
     ByteBuf Unpooled]
    [java.nio
     ByteBuffer]
    [io.netty.handler.codec.http
     DefaultHttpRequest DefaultLastHttpContent
     DefaultHttpResponse DefaultFullHttpRequest
     HttpHeaders HttpUtil  DefaultHttpHeaders HttpContent
     HttpMethod HttpRequest HttpMessage
     HttpResponse HttpResponseStatus
     DefaultHttpContent
     HttpVersion
     LastHttpContent HttpChunkedInput]
    [io.netty.handler.stream
     ChunkedFile ChunkedWriteHandler]
    [java.io
     File
     RandomAccessFile
     Closeable]
    [java.net
     InetSocketAddress]
    [java.util
     Map$Entry]
    [java.util.concurrent
     ConcurrentHashMap]
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
  :query-string (let [uri (.getUri req)]
                  (if (neg? question-mark-index)
                    nil
                    (.substring uri (unchecked-inc question-mark-index))))
  :headers (-> req .headers headers->map)
  :request-method (-> req .getMethod .name str/lower-case keyword)
  :aleph/request-arrived (System/nanoTime)
  :body body
  :scheme (if ssl? :https :http)
  :aleph/keep-alive? (HttpHeaders/isKeepAlive req)
  :server-name (some-> ch ^InetSocketAddress (.localAddress) .getHostName)
  :server-port (some-> ch ^InetSocketAddress (.localAddress) .getPort)
  :remote-addr (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress))

(p/def-derived-map NettyResponse [^HttpResponse rsp complete body]
  :status (-> rsp .getStatus .code)
  :aleph/keep-alive? (HttpHeaders/isKeepAlive rsp)
  :headers (-> rsp .headers headers->map)
  :aleph/complete complete
  :body body)

(defn netty-request->ring-request [^HttpRequest req ssl? ch body]
  (->NettyRequest
    req
    ssl?
    ch
    (AtomicBoolean. false)
    (-> req .getUri (.indexOf (int 63))) body))

(defn netty-response->ring-response [rsp complete body]
  (->NettyResponse rsp complete body))

;;;

(defn try-set-content-length! [^HttpMessage msg ^long length]
  (when-not (-> msg .headers (.contains "Content-Length"))
    (HttpHeaders/setContentLength msg length)))

(defn has-content-length? [^HttpMessage msg]
  (-> msg .headers (.contains "Content-Length")))

(def empty-last-content LastHttpContent/EMPTY_LAST_CONTENT)

(let [ary-class (class (byte-array 0))]
  (defn coerce-element [ch x]
    (if (or
          (instance? String x)
          (instance? ary-class x)
          (instance? ByteBuffer x)
          (instance? ByteBuf x))
      x
      (str x))))

(defn send-streaming-body [ch ^HttpMessage msg body]

  (HttpUtil/setTransferEncodingChunked msg (boolean (not (has-content-length? msg))))
  (netty/write ch msg)

  (if-let [body' (if (sequential? body)

                   (let [buf (netty/allocate ch)
                         pending? (instance? clojure.lang.IPending body)]
                     (loop [s (map (partial coerce-element ch) body)]
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

          sink (netty/sink ch false #(DefaultHttpContent. %))]

      (s/connect src sink)

      (-> ch
        netty/channel
        .closeFuture
        netty/wrap-future
        (d/chain (fn [_] (s/close! src))))

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

(defn send-chunked-file [ch ^HttpMessage msg ^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)
        ci (HttpChunkedInput. (ChunkedFile. raf))]
    (try-set-content-length! msg len)
    (netty/write ch msg)
    (netty/write-and-flush ch ci)))

(defn send-file-region [ch ^HttpMessage msg ^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc 0 len)]
    (try-set-content-length! msg len)
    (netty/write ch msg)
    (netty/write ch fr)
    (netty/write-and-flush ch empty-last-content)))

(defn send-file-body [ch ssl? ^HttpMessage msg ^File file]
  (cond
    ssl?
    (send-streaming-body ch msg
      (-> file
        (bs/to-byte-buffers {:chunk-size 1e6})
        s/->source))

    (-> ch netty/channel .pipeline (.get ChunkedWriteHandler))
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
        (let [code (-> ^HttpResponse msg .getStatus .code)]
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

    (let [^HttpHeaders headers (.headers msg)
          f (cond

              (or
                (nil? body)
                (identical? :aleph/omitted body)
                (instance? String body)
                (instance? ary-class body)
                (instance? ByteBuffer body)
                (instance? ByteBuf body))
              (send-contiguous-body ch msg body)

              (instance? File body)
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
