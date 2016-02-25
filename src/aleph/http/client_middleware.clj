(ns aleph.http.client-middleware
  "This middleware is adapted from clj-http, whose license is amendable to this sort of
   copy/pastery"
  (:refer-clojure :exclude [update])
  (:require
    [potemkin :as p]
    [clojure.stacktrace :refer [root-cause]]
    [clojure.string :as str]
    [clojure.walk :refer [prewalk]]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [byte-streams :as bs]
    [clojure.edn :as edn])
  (:import
    [java.io InputStream]
    [java.net URL URLEncoder UnknownHostException]))

;; Cheshire is an optional dependency, so we check for it at compile time.
(def json-enabled?
  (try
    (require 'cheshire.core)
    true
    (catch Throwable _ false)))

;; Transit is an optional dependency, so check at compile time.
(def transit-enabled?
  (try
    (require 'cognitect.transit)
    true
    (catch Throwable _ false)))

(defn ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [in type & [opts]]
  {:pre [transit-enabled?]}
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)]
    (read (reader in type opts))))

(defn ^:dynamic json-decode
  "Resolve and apply cheshire's json decoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

(defn ^:dynamic json-decode-strict
  "Resolve and apply cheshire's json decoding dynamically (with lazy parsing
  disabled)."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode-strict")) args))

;;;

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (clojure.core/get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn url-encode
  ([s]
    (url-encode s "UTF-8"))
  ([s encoding]
    (URLEncoder/encode s encoding)))

(let [param-? (memoize #(keyword (str (name %) "?")))]
  (defn opt
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

(defn url-encode-illegal-characters
  "Takes a raw url path or query and url-encodes any illegal characters.
  Minimizes ambiguity by encoding space to %20."
  [path-or-query]
  (when path-or-query
    (-> path-or-query
      (str/replace " " "%20")
      (str/replace
        #"[^a-zA-Z0-9\.\-\_\~\!\$\&\'\(\)\*\+\,\;\=\:\@\/\%\?]"
        url-encode))))

(defn parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (url-encode-illegal-characters (.getPath url-parsed))
     :user-info (.getUserInfo url-parsed)
     :query-string (url-encode-illegal-characters (.getQuery url-parsed))}))

(defn nest-params
  [params]
  (prewalk
    #(if (and (vector? %) (map? (second %)))
       (let [[fk m] %]
         (reduce
           (fn [m [sk v]]
             (assoc m
               (str (name fk) \[ (name sk) \]) v))
           {}
           m))
       %)
    params))

;; Statuses for which clj-http will not throw an exception
(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

;; helper methods to determine realm of a response
(defn success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn missing?
  [{:keys [status]}]
  (= status 404))

(defn conflict?
  [{:keys [status]}]
  (= status 409))

(defn redirect?
  [{:keys [status]}]
  (<= 300 status 399))

(defn client-error?
  [{:keys [status]}]
  (<= 400 status 499))

(defn server-error?
  [{:keys [status]}]
  (<= 500 status 599))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-exceptions
  "Middleware that throws a slingshot exception if the response is not a
  regular response. If :throw-entire-message? is set to true, the entire
  response is used as the message, instead of just the status number."
  [client]
  (fn [req]
    (d/let-flow' [{:keys [status] :as rsp} (client req)]
      (if (unexceptional-status? status)
        rsp
        (if (false? (opt req :throw-exceptions))
          rsp
          (d/chain' rsp :aleph/complete
            (fn [_]
              (d/error-deferred (ex-info (str "status: " status) rsp)))))))))

(defn follow-redirect
  "Attempts to follow the redirects from the \"location\" header, if no such
  header exists (bad server!), returns the response without following the
  request."
  [client
   {:keys [uri url scheme server-name server-port trace-redirects]
    :or {trace-redirects []}
    :as req}
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
  thrown if too many redirects occur. Options
  :follow-redirects - default:true, whether to follow redirects
  :max-redirects - default:20, maximum number of redirects to follow
  :force-redirects - default:false, force redirecting methods to GET requests

  In the response:
  :redirects-count - number of redirects
  :trace-redirects - vector of sites the request was redirected from"
  [client
   {:keys [request-method max-redirects redirects-count trace-redirects url]
    :or {redirects-count 0
         ;; max-redirects default taken from Firefox
         max-redirects 20}
    :as req}
   {:keys [status] :as rsp}]
  (let [rsp-r (if (empty? trace-redirects)
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

      (= 307 status)
      (if (or (#{:get :head} request-method)
            (opt req :force-redirects))
        (follow-redirect client
          (assoc req :redirects-count (inc redirects-count)) rsp-r)
        rsp-r)

      :else
      rsp-r)))

;; request decorators, adapted from the clj-http middleware

(defmacro def-decorator [name [key req] & body]
  `(defn ~(with-meta name {:middleware/key (keyword key)})
     [{:keys [~key] :as ~req}]
     (if ~key
       (do ~@body)
       ~req)))

(def-decorator decorate-content-type
  [content-type req]
  (let [ctv (content-type-value content-type)
        ct (if-let [encoding (:character-encoding req)]
             (str ctv "; charset=" encoding)
             ctv)]
    (update-in req [:headers] assoc "content-type" ct)))

(def-decorator decorate-accept
  [accept req]
  (-> req (dissoc :accept)
    (assoc-in [:headers "accept"]
      (content-type-value accept))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(def-decorator decorate-accept-encoding
  [accept-encoding req]
  (-> req (dissoc :accept-encoding)
    (assoc-in [:headers "accept-encoding"]
      (accept-encoding-value accept-encoding))))

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
    (when-let [found (when content-type
                       (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
      (second found))
    "UTF-8"))

(defn generate-query-string [params & [content-type]]
  (if (seq params)
    (let [encoding (detect-charset content-type)
          sb (StringBuffer.)]
      (p/doit [[k v] params]
        (let [k' (url-encode (name k) encoding)]
          (p/doit [x (if (sequential? v) v [v])]
            (.append sb k')
            (.append sb "=")
            (.append sb (url-encode (str x) encoding))
            (.append sb "&"))))
      (.substring sb 0 (unchecked-dec (unchecked-long (.length sb)))))
    ""))

(def-decorator decorate-query-params
  [query-params req]
  (let [content-type (get req :content-type :x-www-form-urlencoded)]
    (-> req
      (dissoc :query-params)
      (update-in [:query-string]
        (fn [old-query-string new-query-string]
          (if-not (empty? old-query-string)
            (str old-query-string "&" new-query-string)
            new-query-string))
        (generate-query-string
          (nest-params query-params)
          (content-type-value content-type))))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))
        bytes (.getBytes ^String basic-auth "UTF-8")]
    (str "Basic "
      (-> ^String basic-auth
        (.getBytes "UTF-8")
        javax.xml.bind.DatatypeConverter/printBase64Binary))))

(def-decorator decorate-basic-auth
  [basic-auth req]
  (-> req
    (dissoc :basic-auth)
    (assoc-in [:headers "authorization"]
      (basic-auth-value basic-auth))))

(def-decorator decorate-oauth
  [oauth-token req]
  (-> req
    (dissoc :oauth-token)
    (assoc-in [:headers "authorization"]
      (str "Bearer " oauth-token))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(def-decorator decorate-user-info
  [user-info req]
  (if-let [[user password] (parse-user-info user-info)]
    (assoc req :basic-auth [user password])
    req))

(def-decorator decorate-method
  [method req]
  (-> req
    (dissoc :method)
    (assoc :request-method method)))

(def-decorator decorate-form-params
  [form-params req]
  (let [{:keys [request-method]} req]
    (if (#{:post :put :patch} request-method)
      (let [content-type (get req :content-type :x-www-form-urlencoded)]
        (-> req
          (dissoc
            :form-params)
          (assoc
            :content-type (content-type-value content-type)
            :body (generate-query-string
                    (nest-params form-params)
                    (content-type-value content-type)))))
      req)))

(defn decorate-nested-params
  [{:keys [query-params form-params content-type] :as req}]
  (if (= :json content-type)
    req
    (reduce
      nest-params
      req
      [:query-params :form-params])))

(defn decorate-url
  [{:keys [url] :as req}]
  (if url
    (-> req
      (dissoc :url)
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

(defn parse-content-type
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
(defmulti coerce-response-body (fn [req _] (:as req)))

(defmethod coerce-response-body :byte-array [_ resp]
  (assoc resp :body (bs/to-byte-array (:body resp))))

(defmethod coerce-response-body :stream [_ resp]
  (update-in resp [:body] bs/to-input-stream))

(defn coerce-json-body
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

(defn coerce-clojure-body
  [request {:keys [body] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")
        body (bs/to-byte-array body)]
    (assoc resp :body (edn/read-string (String. ^"[B" body charset)))))

(defn coerce-transit-body
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

(def default-request-decorators
  [decorate-method
   decorate-url
   decorate-query-params
   decorate-form-params
   decorate-user-info
   decorate-basic-auth
   decorate-oauth
   decorate-accept
   decorate-accept-encoding
   decorate-content-type])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [client]
  (let [client' (-> client
                  wrap-exceptions
                  wrap-request-timing)]
    (fn [req]
      (if (:aleph.http.client/close req)
        (client req)

        (let [req' (reduce #(%2 %1) req default-request-decorators)]
          (d/chain' (client' req')

            ;; coerce the response body
            (fn [{:keys [body] :as rsp}]
              (if body
                (coerce-response-body req' rsp)
                rsp))))))))
