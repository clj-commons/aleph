(ns aleph.http.client-middleware
  "This middleware is adapted from clj-http, whose license is amenable to this sort of
   copy/pastery"
  (:require
    [aleph.http.common :as common]
    ;; leave this dependency to make sure that HeaderMap is already compiled
    [aleph.http.core :as http]
    [aleph.http.schema :as schema]
    [clj-commons.byte-streams :as bs]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :refer [prewalk]]
    [manifold.deferred :as d]
    [manifold.executor :as ex]
    [manifold.stream :as s]
    [potemkin :as p])
  (:import
    (aleph.http.core
      HeaderMap)
    (io.netty.buffer
      ByteBuf
      Unpooled)
    (io.netty.handler.codec Headers)
    (io.netty.handler.codec.base64 Base64)
    (io.netty.handler.codec.http
      HttpHeaderNames
      HttpHeaders)
    (io.netty.handler.codec.http.cookie
      ClientCookieDecoder
      ClientCookieEncoder
      DefaultCookie)
    (java.io
      ByteArrayInputStream
      ByteArrayOutputStream
      InputStream)
    (java.net
      IDN
      URL
      URLDecoder
      URLEncoder)
    (java.nio.charset StandardCharsets)))

;; Cheshire is an optional dependency, so we check for it at compile time.
(def ^:no-doc json-enabled?
  (try
    (require 'cheshire.core)
    true
    (catch Throwable _ false)))

;; Transit is an optional dependency, so check at compile time.
(def ^:no-doc transit-enabled?
  (try
    (require 'cognitect.transit)
    true
    (catch Throwable _ false)))

(defn- transit-opts-by-type
  "Return the Transit options by type."
  [opts type class-name]
  {:pre [transit-enabled?]}
  (cond
    (empty? opts)
    opts
    (contains? opts type)
    (clojure.core/get opts type)
    :else
    (let [class (Class/forName class-name)]
      (println "Deprecated use of :transit-opts found.")
      (update-in opts [:handlers]
                 (fn [handlers]
                   (->> handlers
                        (filter #(instance? class (second %)))
                        (into {})))))))

(defn- transit-read-opts
  "Return the Transit read options."
  [opts]
  {:pre [transit-enabled?]}
  (transit-opts-by-type opts :decode "com.cognitect.transit.ReadHandler"))

(defn- transit-write-opts
  "Return the Transit write options."
  [opts]
  {:pre [transit-enabled?]}
  (transit-opts-by-type opts :encode "com.cognitect.transit.WriteHandler"))

(defn ^:no-doc ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [^InputStream in type & [opts]]
  {:pre [transit-enabled?]}
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)]
    (read (reader in type (transit-read-opts opts)))))

(defn ^:no-doc ^:dynamic transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  {:pre [transit-enabled?]}
  (let [output (ByteArrayOutputStream.)
        writer (ns-resolve 'cognitect.transit 'writer)
        write (ns-resolve 'cognitect.transit 'write)]
    (write (writer output type (transit-write-opts opts)) out)
    (.toByteArray output)))

(defn ^:no-doc ^:dynamic json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

(defn ^:no-doc ^:dynamic json-decode
  "Resolve and apply cheshire's json decoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

(defn ^:no-doc ^:dynamic json-decode-strict
  "Resolve and apply cheshire's json decoding dynamically (with lazy parsing
  disabled)."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode-strict")) args))

;;;

(defn ^:no-doc when-pos [v]
  (when (and v (pos? v)) v))

(defn ^:no-doc url-encode
  ([^String s]
   (url-encode s "UTF-8"))
  ([^String s ^String encoding]
   (URLEncoder/encode s encoding)))

(let [param-? (memoize #(keyword (str (name %) "?")))]
  (defn ^:no-doc opt
    "Check the request parameters for a keyword  boolean option, with or without
     the ?

     Returns false if either of the values are false, or the value of
     (or key1 key2) otherwise (truthy)"
    [req param]
    (let [param' (param-? param)
          v1 (clojure.core/get req param)
          v2 (clojure.core/get req param')]
      (if (false? v1)
        false
        (if (false? v2)
          false
          (or v1 v2))))))

;;;

(defn ^:no-doc url-encode-illegal-characters
  "Takes a raw url path or query and url-encodes any illegal characters.
  Minimizes ambiguity by encoding space to %20."
  [path-or-query]
  (when path-or-query
    (-> path-or-query
        (str/replace " " "%20")
        (str/replace
          #"[^a-zA-Z0-9\.\-\_\~\!\$\&\'\(\)\*\+\,\;\=\:\@\/\%\?]"
          url-encode))))

(defn ^:no-doc parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)
        orig-path (.getPath url-parsed)
        ;; HTTP/2 RFC 9113 requires the :path pseudo-header never be empty.
        path (if (.isEmpty orig-path)
               "/"
               (url-encode-illegal-characters orig-path))]
    {:scheme       (keyword (.getProtocol url-parsed))
     :server-name  (.getHost url-parsed)
     :server-port  (when-pos (.getPort url-parsed))
     ;; Actually the URI's path, not a full URI. The Ring spec is wrong.
     :uri          path
     :path         path
     :user-info    (when-let [user-info (.getUserInfo url-parsed)]
                     (URLDecoder/decode user-info "UTF-8"))
     :query-string (url-encode-illegal-characters (.getQuery url-parsed))}))

(defn- nest-params
  [request param-key]
  (if-let [params (request param-key)]
    (assoc request param-key
           (prewalk
             #(if (and (vector? %) (map? (second %)))
                (let [[fk m] %]
                  (reduce
                    (fn [m [sk v]]
                      (assoc m (str (name fk) "[" (name sk) "]") v))
                    {}
                    m))
                %)
             params))
    request))

(defn wrap-nested-params
  "Middleware wrapping nested parameters for query strings."
  [{:keys [content-type flatten-nested-keys] :as req}]
  (when (and (some? flatten-nested-keys)
             (or (some? (opt req :ignore-nested-query-string))
                 (some? (opt req :flatten-nested-form-params))))
    (throw (IllegalArgumentException.
             (str "only :flatten-nested-keys or :ignore-nested-query-string/"
                  ":flatten-nested-keys may be specified, not both"))))
  (let [form-urlencoded? (or (nil? content-type)
                             (= content-type :x-www-form-urlencoded))
        flatten-form? (opt req :flatten-nested-form-params)
        nested-keys (or flatten-nested-keys
                        (cond-> []
                                (not (opt req :ignore-nested-query-string))
                                (conj :query-params)

                                (and form-urlencoded?
                                     (or (nil? flatten-form?)
                                         (true? flatten-form?)))
                                (conj :form-params)))]
    (reduce nest-params req nested-keys)))

;; Statuses for which clj-http will not throw an exception
(def ^:no-doc unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307 308})

;; helper methods to determine realm of a response
(defn ^:no-doc success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn ^:no-doc missing?
  [{:keys [status]}]
  (= status 404))

(defn ^:no-doc conflict?
  [{:keys [status]}]
  (= status 409))

(defn ^:no-doc redirect?
  [{:keys [status]}]
  (<= 300 status 399))

(defn ^:no-doc client-error?
  [{:keys [status]}]
  (<= 400 status 499))

(defn ^:no-doc server-error?
  [{:keys [status]}]
  (<= 500 status 599))

(defn ^:no-doc content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn error-status-deferred [rsp]
  (d/error-deferred
   (ex-info
    (str "status: " (:status rsp))
    (assoc rsp :aleph/error-status? true))))

(defn handle-error-status
  "Middleware that throws response as an ExceptionInfo if the response has
  unsuccessful status code. :throw-exceptions set to false in the request
  disables this middleware."
  [req rsp]
  (let [{:keys [status body]} rsp]
    (cond
      (unexceptional-status? status)
      rsp

      (false? (opt req :throw-exceptions))
      rsp

      (instance? InputStream body)
      (d/chain' (d/future (bs/to-byte-array body))
                (fn [body]
                  (error-status-deferred
                   (assoc rsp :body (ByteArrayInputStream. body)))))

      (nil? body)
      (error-status-deferred rsp)

      :else
      (d/chain'
       (s/reduce conj [] body)
       (fn [body]
         (error-status-deferred
          (assoc rsp :body (s/->source body))))))))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [req]
  (if-let [m (:method req)]
    (-> req
        (dissoc :method)
        (assoc :request-method m))
    req))

(defn ^:no-doc follow-redirect
  "Attempts to follow the redirects from the \"location\" header, if no such
  header exists (bad server!), returns the response without following the
  request."
  [client
   {:keys [uri url scheme server-name server-port trace-redirects]
    :or   {trace-redirects []}
    :as   req}
   {:keys [body] :as rsp}]
  (let [url (or url (str (name scheme) "://" server-name
                         (when server-port (str ":" server-port)) uri))]
    (if-let [raw-redirect (get-in rsp [:headers "location"])]
      (let [redirect (str (URL. (URL. url) raw-redirect))]
        (client
          (-> req
              (dissoc :query-params)
              (assoc :url redirect)
              (assoc :trace-redirects (conj trace-redirects redirect)))))
      ;; Oh well, we tried, but if no location is set, return the response
      rsp)))

(defn handle-redirects
  "Middleware that follows redirects in the response. A slingshot exception is
  thrown if too many redirects occur. Options:

  :follow-redirects - default:true, whether to follow redirects
  :max-redirects - default:20, maximum number of redirects to follow
  :force-redirects - default:false, force redirecting methods to GET requests

  In the response:
  :redirects-count - number of redirects
  :trace-redirects - vector of sites the request was redirected from"
  [client req rsp]
  ;; The req passed to this fn is the req from BEFORE standard middleware was
  ;; applied, so we may have to fix :method again
  (let [req (wrap-method req)
        {:keys [request-method max-redirects redirects-count trace-redirects url]
         :or   {redirects-count 0
                ;; max-redirects default taken from Firefox
                max-redirects   20}} req
        status (:status rsp)
        rsp-r (if (empty? trace-redirects)
                rsp
                (assoc rsp :trace-redirects trace-redirects))]
    (cond
      (false? (opt req :follow-redirects))
      rsp

      (not (redirect? rsp-r))
      rsp-r

      (and max-redirects (> redirects-count max-redirects))
      (if (opt req :throw-exceptions)
        (throw (ex-info (str "too many redirects: " redirects-count) req))
        rsp-r)

      (= 303 status)
      (follow-redirect client
                       (assoc req
                              :request-method :get
                              :redirects-count (inc redirects-count))
                       rsp-r)


      (#{301 302} status)
      (cond
        (#{:get :head} request-method)
        (follow-redirect client
                         (assoc req
                                :redirects-count (inc redirects-count))
                         rsp-r)

        (opt req :force-redirects)
        (follow-redirect client
                         (assoc req
                                :request-method :get
                                :redirects-count (inc redirects-count))
                         rsp-r)

        :else
        rsp-r)

      (#{307 308} status)
      (if (or (#{:get :head} request-method)
              (opt req :force-redirects))
        (follow-redirect client
                         (assoc req :redirects-count (inc redirects-count)) rsp-r)
        rsp-r)

      :else
      rsp-r)))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [{:keys [content-type character-encoding] :as req}]
  (if content-type
    (let [ctv (content-type-value content-type)
          ct (if character-encoding
               (str ctv "; charset=" character-encoding)
               ctv)]
      (update-in req [:headers] assoc "content-type" ct))
    req))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [{:keys [accept] :as req}]
  (if accept
    (-> req
        (dissoc :accept)
        (assoc-in [:headers "accept"]
                  (content-type-value accept)))
    req))

(defn ^:no-doc accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [{:keys [accept-encoding] :as req}]
  (if accept-encoding
    (-> req (dissoc :accept-encoding)
        (assoc-in [:headers "accept-encoding"]
                  (accept-encoding-value accept-encoding)))
    req))

(defn ^:no-doc detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
    (when-let [found (when content-type
                       (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
      (second found))
    "UTF-8"))

(defn ^:no-doc multi-param-suffix [index multi-param-style]
  (case multi-param-style
    :indexed (str "[" index "]")
    :array "[]"
    :default ""))

(defn ^:no-doc generate-query-string-with-encoding
  ([params encoding]
   (generate-query-string-with-encoding params encoding :default))
  ([params encoding multi-param-style]
   (str/join "&"
             (mapcat (fn [[k v]]
                       (if (sequential? v)
                         (map-indexed
                           #(str (url-encode (name k) encoding)
                                 (multi-param-suffix %1 multi-param-style)
                                 "="
                                 (url-encode (str %2) encoding))
                           v)
                         [(str (url-encode (name k) encoding)
                               "="
                               (url-encode (str v) encoding))]))
                     params))))

(defn ^:no-doc generate-query-string [params & [content-type multi-param-style]]
  (let [encoding (detect-charset content-type)]
    (generate-query-string-with-encoding params encoding (or multi-param-style :default))))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [{:keys [query-params content-type multi-param-style]
    :or   {content-type      :x-www-form-urlencoded
           multi-param-style :default}
    :as   req}]
  (if (nil? query-params)
    req
    (-> req
        (dissoc :query-params)
        (update-in [:query-string]
                   (fn [old-query-string new-query-string]
                     (if-not (empty? old-query-string)
                       (str old-query-string "&" new-query-string)
                       new-query-string))
                   (generate-query-string
                     query-params
                     (content-type-value content-type)
                     multi-param-style)))))

(defn ^:no-doc basic-auth-value
  "Accept a String of the form \"username:password\" or a vector of 2 strings [username password], return a String with the basic auth header (see https://tools.ietf.org/html/rfc2617#page-5)"
  [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))
        input-bytebuf (Unpooled/wrappedBuffer (.getBytes ^String basic-auth "UTF-8"))
        base64-bytebuf (Base64/encode input-bytebuf false)
        base64-string (.toString ^ByteBuf base64-bytebuf StandardCharsets/UTF_8)]
    (.release ^ByteBuf input-bytebuf)
    (.release ^ByteBuf base64-bytebuf)
    (str "Basic " base64-string)))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [req]
  (if-let [basic-auth (:basic-auth req)]
    (-> req
        (dissoc :basic-auth)
        (assoc-in [:headers "authorization"]
                  (basic-auth-value basic-auth)))
    req))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [req]
  (if-let [oauth-token (:oauth-token req)]
    (-> req (dissoc :oauth-token)
        (assoc-in [:headers "authorization"]
                  (str "Bearer " oauth-token)))
    req))

(defn ^:no-doc parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [req]
  (if-let [[user password] (parse-user-info (:user-info req))]
    (assoc req :basic-auth [user password])
    req))

(defmulti ^:no-doc coerce-form-params
          (fn [req] (keyword (content-type-value (:content-type req)))))

(defmethod coerce-form-params :application/edn
  [{:keys [form-params]}]
  (pr-str form-params))

(defn- coerce-transit-form-params [type {:keys [form-params transit-opts]}]
  (when-not transit-enabled?
    (throw (ex-info (format (str "Can't encode form params as "
                                 "\"application/transit+%s\". "
                                 "Transit dependency not loaded.")
                            (name type))
                    {:type         :transit-not-loaded
                     :form-params  form-params
                     :transit-opts transit-opts
                     :transit-type type})))
  (transit-encode form-params type transit-opts))

(defmethod coerce-form-params :application/transit+json [req]
  (coerce-transit-form-params :json req))

(defmethod coerce-form-params :application/transit+msgpack [req]
  (coerce-transit-form-params :msgpack req))

(defmethod coerce-form-params :application/json
  [{:keys [form-params json-opts]}]
  (when-not json-enabled?
    (throw (ex-info (str "Can't encode form params as \"application/json\". "
                         "Cheshire dependency not loaded.")
                    {:type        :cheshire-not-loaded
                     :form-params form-params
                     :json-opts   json-opts})))
  (json-encode form-params json-opts))

(defmethod coerce-form-params :default [{:keys [content-type
                                                form-params
                                                form-param-encoding
                                                multi-param-style]
                                         :or   {multi-param-style :default}}]
  (if form-param-encoding
    (generate-query-string-with-encoding form-params form-param-encoding multi-param-style)
    (generate-query-string form-params (content-type-value content-type) multi-param-style)))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [{:keys [form-params content-type request-method]
    :or   {content-type :x-www-form-urlencoded}
    :as   req}]

  (if (and form-params (#{:post :put :patch :delete} request-method))
    (-> req
        (dissoc :form-params)
        (assoc :content-type (content-type-value content-type)
               :body (coerce-form-params req)))
    req))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [req]
  (if-let [url (:url req)]
    (-> req
        (dissoc :url)
        (assoc :request-url url)
        (merge (parse-url url)))
    req))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in milliseconds)
  of the request into the :request-time key in the response."
  [client]
  (fn [req]
    (let [start (System/currentTimeMillis)]
      (-> (client req)
          (d/chain' #(assoc % :request-time (- (System/currentTimeMillis) start)))))))

(def ^:no-doc ^String cookie-header-name (str HttpHeaderNames/COOKIE))
(def ^:no-doc ^String set-cookie-header-name (str HttpHeaderNames/SET_COOKIE))

;; That's a pretty liberal domain check.
;; Under RFC2965 your domain should contain leading "." to match successors,
;; but this extra dot is ignored by more recent specifications.
;; So, if you need obsolete RFC2965 compatible behavior, feel free to
;; plug your one `CookieSpec` with redefined `match-cookie` impl.
(defn ^:no-doc match-cookie-domain? [origin domain]
  (let [origin' (if (str/starts-with? origin ".") origin (str "." origin))
        domain' (if (str/starts-with? domain ".") domain (str "." domain))]
    (str/ends-with? origin' domain')))

;; Reimplementation of org.apache.http.impl.cookie.BasicPathHandler path match logic
(defn ^:no-doc match-cookie-path? [origin-path cookie-path]
  (let [norm-path (if (and (not= "/" cookie-path) (= \/ (last cookie-path)))
                    (subs cookie-path 0 (dec (count cookie-path)))
                    cookie-path)]
    (and (str/starts-with? origin-path norm-path)
         (or (= "/" norm-path)
             (= (count origin-path) (count norm-path))
             (= \/ (-> origin-path (subs (count norm-path)) first))))))


(let [uri->path (fn [uri]
                  (cond
                    (nil? uri) "/"
                    (str/starts-with? uri "/") uri
                    :else (str "/" uri)))]

  (defn ^:no-doc req->cookie-origin [{:keys [url] :as req}]
    (if (some? url)
      (let [{:keys [server-name server-port uri scheme]} (parse-url url)]
        {:host    server-name
         :port    server-port
         :secure? (= :https scheme)
         :path    (uri->path uri)})
      {:host    (some-> (or (:host req) (:server-name req)) IDN/toASCII)
       :port    (or (:port req) (:server-port req) -1)
       :secure? (= :https (or (:scheme req) :http))
       :path    (uri->path (:uri req))})))

(defn ^:no-doc cookie->netty-cookie [{:keys [domain http-only? secure? max-age name path value]}]
  (doto (DefaultCookie. name value)
        (.setDomain domain)
        (.setPath path)
        (.setHttpOnly (or http-only? false))
        (.setSecure (or secure? false))
        (.setMaxAge (or max-age io.netty.handler.codec.http.cookie.Cookie/UNDEFINED_MAX_AGE))))

(p/def-derived-map ^:no-doc Cookie
                   [^DefaultCookie cookie]
                   :domain (.domain cookie)
                   :http-only? (.isHttpOnly cookie)
                   :secure? (.isSecure cookie)
                   :max-age (let [max-age (.maxAge cookie)]
                              (when-not (= max-age io.netty.handler.codec.http.cookie.Cookie/UNDEFINED_MAX_AGE)
                                max-age))
                   :name (.name cookie)
                   :path (.path cookie)
                   :value (.value cookie))

(alter-meta! #'->Cookie assoc :private true)

(defn ^:no-doc netty-cookie->cookie [^DefaultCookie cookie]
  (->Cookie cookie))

(defn ^:no-doc cookie-expired? [{:keys [created max-age]}]
  (cond
    (nil? max-age) false
    (>= 0 max-age) true
    (nil? created) false
    :else (>= (System/currentTimeMillis) (+ created max-age))))

(defprotocol ^:no-doc CookieSpec
  "Implement rules for accepting and returning cookies"
  (parse-cookie [this cookie-str])
  (write-cookies [this cookies])
  (match-cookie-origin? [this origin cookie]))

(defprotocol ^:no-doc CookieStore
  (get-cookies [this])
  (add-cookies! [this cookies]))

(def ^:no-doc default-cookie-spec
  "Default cookie spec implementation providing RFC6265 compliant behavior
   with no validation for cookie names and values. In case you need strict validation
   feel free to create impl. using {ClientCookieDecoder,ClientCookiEncoder}/STRICT
   instead of LAX instances"
  (reify CookieSpec
    (parse-cookie [_ cookie-str]
      (.decode ClientCookieDecoder/LAX cookie-str))
    (write-cookies [_ cookies]
      (.encode ClientCookieEncoder/LAX ^Iterable cookies))
    (match-cookie-origin? [_ origin {:keys [domain path secure?] :as cookie}]
      (cond
        (and secure? (not (:secure? origin)))
        false

        (and (some? domain)
             (not (match-cookie-domain? (:host origin) domain)))
        false

        (and (some? path)
             (not (match-cookie-path? (:path origin) path)))
        false

        (cookie-expired? cookie)
        false

        :else
        true))))

(defn ^:no-doc merge-cookies [stored-cookies new-cookies]
  (reduce (fn [cookies {:keys [domain path name] :as cookie}]
            (assoc-in cookies [domain path name] cookie))
          stored-cookies
          new-cookies))

(defn ^:no-doc enrich-with-current-time [cookies]
  (let [now (System/currentTimeMillis)]
    (map #(assoc % :created now) cookies)))

(defn ^:no-doc in-memory-cookie-store
  "In-memory storage to maintain cookies across requests"
  ([] (in-memory-cookie-store []))
  ([seed-cookies]
   (let [store (atom (merge-cookies {} (enrich-with-current-time seed-cookies)))]
     (reify CookieStore
       (get-cookies [_]
         (->> @store
              (mapcat (fn [[domain cookies]]
                        (sort-by first cookies)))
              (mapcat second)                               ;; unwrap by path
              (map second)))
       (add-cookies! [_ cookies]
         (swap! store merge-cookies (enrich-with-current-time cookies)))))))

(defn ^:no-doc decode-set-cookie-header
  ([header]
   (decode-set-cookie-header default-cookie-spec header))
  ([cookie-spec header]
   (some->> header
            (parse-cookie cookie-spec)
            (netty-cookie->cookie))))

(defn ^:no-doc extract-cookies-from-response-headers
  ([headers]
   (extract-cookies-from-response-headers default-cookie-spec headers))
  ([cookie-spec ^HeaderMap header-m]
   (->> (common/get-header-values header-m set-cookie-header-name)
        (map (partial decode-set-cookie-header cookie-spec)))))

(defn ^:no-doc handle-cookies [{:keys [cookie-store cookie-spec]
                                :or   {cookie-spec default-cookie-spec}}
                               {:keys [headers] :as rsp}]
  (if (nil? cookie-store)
    rsp
    (let [cookies (extract-cookies-from-response-headers cookie-spec headers)]
      (when-not (empty? cookies)
        (add-cookies! cookie-store cookies))
      ;; pairing with what clj_http does with parsed cookies
      ;; (as it's impossible to tell what cookies were parsed
      ;; from this particular response, you will be forced
      ;; to parse header twice otherwise)
      (assoc rsp :cookies cookies))))

(defn ^:no-doc reduce-to-unique-cookie-names [cookies]
  (when-not (empty? cookies)
    (->> cookies
         (map (juxt :name identity))
         (into {})
         vals)))

(defn ^:no-doc write-cookie-header [cookies cookie-spec req]
  (if (empty? cookies)
    req
    (let [header (->> cookies
                      (map cookie->netty-cookie)
                      (write-cookies cookie-spec))]
      (assoc-in req [:headers cookie-header-name] header))))

(defn ^:no-doc add-cookie-header [cookie-store cookie-spec req]
  (let [origin (req->cookie-origin req)
        cookies (->> (get-cookies cookie-store)
                     (filter (partial match-cookie-origin? cookie-spec origin))
                     ;; note that here we rely on cookie store implementation,
                     ;; which might be not the best idea
                     (reduce-to-unique-cookie-names))]
    (write-cookie-header cookies cookie-spec req)))

(defn wrap-cookies
  "Middleware that set 'Cookie' header based on the content of cookies passed
   with the request or from cookies storage (when provided). Source for 'Cookie'
   header content by priorities:

   * 'Cookie' header (already set)
   * non-empty `:cookies`
   * non-nil `:cookie-store`

   Each cookie should be represented as a map:

   |:---|:---
   | `name` | name of this cookie
   | `value` | value of this cookie
   | `domain` | specifies allowed hosts to receive the cookie (including subdomains)
   | `path` | indicates a URL path that must exist in the requested URL in order to send the 'Cookie' header
   | `http-only?` | when set to `true`, cookie can only be accessed by HTTP. Optional, defaults to `false`
   | `secure?` | when set to `true`, cookie can only be transmitted over an encrypted connection. Optional, defaults to `false`
   | `max-age` | set maximum age of this cookie in seconds. Options, defaults to `io.netty.handler.codec.http.cookie.Cookie/UNDEFINED_MAX_AGE`."
  [{:keys [cookie-store cookie-spec cookies]
    :or   {cookie-spec default-cookie-spec
           cookies     '()}
    :as   req}]
  (cond
    (some? (get-in req [:headers cookie-header-name]))
    req

    (seq cookies)
    (write-cookie-header cookies cookie-spec req)

    (some? cookie-store)
    (add-cookie-header cookie-store cookie-spec req)

    :else
    req))

(defn ^:no-doc parse-content-type
  "Parse `s` as an RFC 2616 media type."
  [s]
  (if-let [m (re-matches #"\s*(([^/]+)/([^ ;]+))\s*(\s*;.*)?" (str s))]
    {:content-type (keyword (nth m 1))
     :content-type-params
     (->> (str/split (str (nth m 4)) #"\s*;\s*")
          (identity)
          (remove str/blank?)
          (map #(str/split % #"="))
          (mapcat (fn [[k v]] [(keyword (str/lower-case k)) (str/trim v)]))
          (apply hash-map))}))

;; Multimethods for coercing body type to the :as key
(defmulti ^:no-doc coerce-response-body (fn [req _] (:as req)))

(defmethod coerce-response-body :byte-array [_ resp]
  (update resp :body bs/to-byte-array))

(defmethod coerce-response-body :stream [_ resp]
  (update-in resp [:body] bs/to-input-stream))

(defn ^:no-doc coerce-json-body
  [{:keys [coerce] :as req} {:keys [body status] :as resp} keyword? strict? & [charset]]
  (let [^String charset (or charset (-> resp :content-type-params :charset)
                            "UTF-8")
        body (bs/to-byte-array body)
        decode-func (if strict? json-decode-strict json-decode)]
    (if json-enabled?
      (cond
        (= coerce :always)
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (unexceptional-status? status)
             (or (nil? coerce) (= coerce :unexceptional)))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (not (unexceptional-status? status)) (= coerce :exceptional))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        :else (assoc resp :body (String. ^"[B" body charset)))
      (assoc resp :body (String. ^"[B" body charset)))))

(defn ^:no-doc coerce-clojure-body
  [request {:keys [body] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")
        body (bs/to-byte-array body)]
    (assoc resp :body (edn/read-string (String. ^"[B" body charset)))))

(defn ^:no-doc coerce-transit-body
  [{:keys [transit-opts] :as request} {:keys [body] :as resp} type]
  (if transit-enabled?
    (assoc resp :body (parse-transit body type transit-opts))
    resp))

(defmethod coerce-response-body :json [req resp]
  (coerce-json-body req resp true false))

(defmethod coerce-response-body :json-strict [req resp]
  (coerce-json-body req resp true true))

(defmethod coerce-response-body :json-strict-string-keys [req resp]
  (coerce-json-body req resp false true))

(defmethod coerce-response-body :json-string-keys [req resp]
  (coerce-json-body req resp false false))

(defmethod coerce-response-body :clojure [req resp]
  (coerce-clojure-body req resp))

(defmethod coerce-response-body :transit+json [req resp]
  (coerce-transit-body req resp :json))

(defmethod coerce-response-body :transit+msgpack [req resp]
  (coerce-transit-body req resp :msgpack))

(defmethod coerce-response-body :string [{:keys [as]} {:keys [body] :as resp}]
  (let [body-bytes (bs/to-byte-array body)]
    (if (string? as)
      (assoc resp :body (bs/to-string body-bytes {:charset as}))
      (assoc resp :body (bs/to-string body-bytes)))))

(defmethod coerce-response-body :default [_ resp]
  resp)

(defn wrap-request-debug [req]
  (cond-> req
          (opt req :save-request)
          (assoc :aleph/save-request-message (atom nil))

          (opt req :debug-body)
          (assoc :aleph/save-request-body (atom nil))))

(defn ^:no-doc handle-response-debug [req rsp]
  (let [saved-message (get req :aleph/save-request-message)
        saved-body (get req :aleph/save-request-body)
        req' (dissoc req
                     :aleph/save-request-body
                     :aleph/save-request-message
                     :save-request
                     :save-request?
                     :debug-body
                     :debug-body?)]
    (cond-> rsp
            (some? saved-message)
            (assoc :aleph/netty-request @saved-message)

            (some? saved-body)
            (assoc :aleph/request-body @saved-body)

            (opt req :save-request)
            (assoc :aleph/request req'))))

(defn ^:no-doc wrap-validation [req]
  (when-not (schema/valid-request? req)
    (throw (IllegalArgumentException. (format "Invalid spec: %s" (schema/explain-request req)))))
  req)

(def ^:no-doc default-middleware
  [wrap-method
   wrap-url
   wrap-validation
   wrap-nested-params
   wrap-query-params
   wrap-form-params
   wrap-user-info
   wrap-basic-auth
   wrap-oauth
   wrap-accept
   wrap-accept-encoding
   wrap-content-type
   wrap-cookies
   wrap-request-debug])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [client]
  (let [client' (-> client
                    wrap-request-timing)]
    (fn [req]
      (let [executor (ex/executor)]
        (if (:aleph.http.client/close req)
          (client req)

          (let [req' (reduce #(%2 %1) req default-middleware)]
            (d/chain' (client' req')

                      ;; coerce the response body
                      (fn [{:keys [body] :as rsp}]
                        (let [rsp' (handle-response-debug req' rsp)]
                          (if (and (some? body) (some? (:as req')))
                            (d/future-with (or executor (ex/wait-pool))
                              (coerce-response-body req' rsp'))
                            rsp'))))))))))
