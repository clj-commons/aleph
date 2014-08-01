(ns aleph.http.core
  (:require
    [potemkin :as p]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [clojure.string :as str]
    [clojure.set :as set])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]
    [java.net
     InetSocketAddress]
    [java.util
     Map$Entry]
    [io.netty.channel
     Channel]
    [io.netty.handler.codec.http
     HttpMessage
     HttpHeaders
     DefaultHttpHeaders
     DefaultFullHttpResponse
     HttpRequest
     HttpVersion
     HttpResponseStatus
     DefaultHttpResponse]))

(def non-standard-keys
  {"content-md5" "Content-MD5"
   "etag" "ETag"
   "www-authenticate" "WWW-Authenticate"
   "x-xss-protection" "X-XSS-Protection"
   "x-webkit-csp" "X-WebKit-CSP"
   "x-ua-compatible" "X-UA-Compatible"
   "x-att-deviceid" "X-ATT-DeviceId"
   "dnt" "DNT"})

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

(defn map->headers! [m ^HttpHeaders h]
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
      (map->headers! m (.headers rsp)))
    rsp))

(p/def-derived-map NettyRequest [^HttpRequest req ^Channel ch body]
  :scheme :http
  :keep-alive? (HttpHeaders/isKeepAlive req)
  :request-method (-> req .getMethod .name str/lower-case keyword)
  :headers (-> req .headers headers->map)
  :uri (-> req .getUri)
  ;:query-string (-> req .getQueryString)
  :server-name (some-> ch ^InetSocketAddress (.localAddress) .getHostName)
  :server-port (some-> ch ^InetSocketAddress (.localAddress) .getPort)
  :remote-addr (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .toString)
  :body body)

(defn netty-request->ring-request [req ch body]
  (->NettyRequest req ch body))
