(ns aleph.http.core
  (:require
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [clojure.set :as set]
    [clojure.string :as str]
    [potemkin :as p])
  (:import
    [io.netty.channel
     Channel
     DefaultFileRegion
     ChannelFuture
     ChannelFutureListener]
    [io.netty.buffer
     ByteBuf Unpooled]
    [java.nio
     ByteBuffer]
    [io.netty.handler.codec.http
     DefaultHttpRequest DefaultLastHttpContent
     DefaultHttpResponse DefaultFullHttpRequest
     HttpHeaders HttpContent
     HttpMethod HttpRequest HttpMessage
     HttpResponse HttpResponseStatus
     DefaultHttpContent
     HttpVersion
     LastHttpContent]
    [java.io
     File
     RandomAccessFile
     Closeable]
    [java.net
     InetSocketAddress]
    [java.util
     Map$Entry]
    [java.util.concurrent
     ConcurrentHashMap]))

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
      ks)))

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
                 (str/join "-")))]

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
      (set (keys removed))))
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
              vals (->> headers
                     .entries
                     (filter #(= k' (str/lower-case (.getKey ^Map$Entry %))))
                     (mapv #(.getValue ^Map$Entry %)))]
          (cond
            (= 0 (count vals))
            default-value

            (= 1 (count vals))
            (nth vals 0)

            :else
            vals))))))

(defn headers->map [^HttpHeaders h]
  (HeaderMap. h nil nil nil))

(defn map->headers! [^HttpHeaders h m]
  (doseq [e m]
    (let [k (normalize-header-key (key e))
          v (val e)]
      (if (sequential? v)
        (.add h ^String k ^Iterable v)
        (.add h ^String k ^Object v)))))

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
  (prn m)
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

(p/def-derived-map NettyRequest [^HttpRequest req ssl? ^Channel ch body]
  :scheme (if ssl? :https :http)
  :keep-alive? (HttpHeaders/isKeepAlive req)
  :request-method (-> req .getMethod .name str/lower-case keyword)
  :headers (-> req .headers headers->map)
  :uri (-> req .getUri)
  :query-string (let [uri (.getUri req)
                      idx (.indexOf uri (int \?))]
                  (if (neg? idx)
                    nil
                    (.substring uri (unchecked-inc idx))))
  :server-name (some-> ch ^InetSocketAddress (.localAddress) .getHostName)
  :server-port (some-> ch ^InetSocketAddress (.localAddress) .getPort)
  :remote-addr (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress)
  :body body)

(p/def-derived-map NettyResponse [^HttpResponse rsp body]
  :status (-> rsp .getStatus .code)
  :keep-alive? (HttpHeaders/isKeepAlive rsp)
  :headers (-> rsp .headers headers->map)
  :body body)

(defn netty-request->ring-request [req ssl? ch body]
  (->NettyRequest req ssl? ch body))

(defn netty-response->ring-response [rsp body]
  (->NettyResponse rsp body))

;;;

(def empty-last-content LastHttpContent/EMPTY_LAST_CONTENT)

(let [ary-class (class (byte-array 0))]
  (defn coerce-element [x]
    (if (or
          (instance? String x)
          (instance? ary-class x)
          (instance? ByteBuffer x)
          (instance? ByteBuf x))
      (netty/to-byte-buf x)
      (netty/to-byte-buf (str x)))))

(defn send-streaming-body [^Channel ch ^HttpMessage msg body]

  (HttpHeaders/setTransferEncodingChunked msg)
  (netty/write ch msg)

  (if-let [body' (if (sequential? body)

                   ;; just unroll the seq, if we can
                   (loop [s (map coerce-element body)]
                     (if (empty? s)
                       (do
                         (.flush ch)
                         nil)
                       (if (or (not (instance? clojure.lang.IPending s))
                             (realized? s))
                         (let [x (first s)]
                           (netty/write ch (netty/to-byte-buf x))
                           (recur (rest s)))
                         body)))

                   (do
                     (.flush ch)
                     body))]

    (let [src (if (or (sequential? body') (s/stream? body'))
                (->> body'
                  s/->source
                  (s/map (fn [x]
                           (try
                             (netty/to-byte-buf x)
                             (catch Throwable e
                               (log/error e "error converting " (.getName (class x)) " to ByteBuf")
                               (.close ch))))))
                (netty/to-byte-buf-stream body' 8192))

          sink (netty/sink ch false #(DefaultHttpContent. %))]

      (s/connect src sink)

      (let [d (d/deferred)]
        (s/on-closed sink #(d/success! d true))
        (d/chain' d
          (fn [_]
            (when (instance? Closeable body)
              (.close ^Closeable body))
            (netty/write-and-flush ch empty-last-content)))))

    (netty/write-and-flush ch empty-last-content)))

(defn send-file-body [^Channel ch ^HttpMessage msg ^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc 0 len)]
    (HttpHeaders/setContentLength msg len)
    (netty/write ch msg)
    (netty/write ch fr)
    (netty/write-and-flush ch empty-last-content)))

(defn send-contiguous-body [^Channel ch ^HttpMessage msg body]
  (let [body (if body
               (DefaultLastHttpContent. (netty/to-byte-buf body))
               empty-last-content)]
    (HttpHeaders/setContentLength msg (-> ^HttpContent body .content .readableBytes))
    (netty/write ch msg)
    (netty/write-and-flush ch body)))

(let [ary-class (class (byte-array 0))]
  (defn send-message
    [^Channel ch keep-alive? ^HttpMessage msg body]

    (let [^HttpHeaders headers (.headers msg)]

      (let [f (cond

                (or
                  (nil? body)
                  (instance? String body)
                  (instance? ary-class body)
                  (instance? ByteBuffer body)
                  (instance? ByteBuf body))
                (send-contiguous-body ch msg body)

                (instance? File body)
                (send-file-body ch msg body)

                :else
                (let [class-name (.getName (class body))]
                  (try
                    (send-streaming-body ch msg body)
                    (catch Throwable e
                      (log/error e "error sending body of type " class-name)))))]

        (when-not keep-alive?
          (-> f
            (d/chain'
              (fn [^ChannelFuture f]
                (.addListener f ChannelFutureListener/CLOSE)))
            (d/catch Throwable #(log/error % "err"))))

        f))))
