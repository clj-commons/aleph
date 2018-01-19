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
    [manifold.executor :as ex]
    [byte-streams :as bs]
    [clojure.edn :as edn])
  (:import
   [io.netty.buffer ByteBuf Unpooled]
   [io.netty.handler.codec.base64 Base64]
   [java.io InputStream ByteArrayOutputStream ByteArrayInputStream]
   [java.nio.charset StandardCharsets]
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

(defn ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [in type & [opts]]
  {:pre [transit-enabled?]}
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)]
    (read (reader in type (transit-read-opts opts)))))

(defn ^:dynamic transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  {:pre [transit-enabled?]}
  (let [output (ByteArrayOutputStream.)
        writer (ns-resolve 'cognitect.transit 'writer)
        write (ns-resolve 'cognitect.transit 'write)]
    (write (writer output type (transit-write-opts opts)) out)
    (.toByteArray output)))

(defn ^:dynamic json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

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
  [{:keys [content-type] :as req}]
  (if (or (nil? content-type)
          (= content-type :x-www-form-urlencoded))
    (reduce
     nest-params
     req
     [:query-params :form-params])
    req))

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
    (d/let-flow' [{:keys [status body] :as rsp} (client req)]
      (if (unexceptional-status? status)
        rsp
        (cond

          (false? (opt req :throw-exceptions))
          rsp

          (instance? InputStream body)
          (d/chain' (d/future (bs/to-byte-array body))
            (fn [body]
              (d/error-deferred
                (ex-info
                  (str "status: " status)
                  (assoc rsp :body (ByteArrayInputStream. body))))))

          :else
          (d/chain'
            (s/reduce conj [] body)
            (fn [body]
              (d/error-deferred
                (ex-info
                  (str "status: " status)
                  (assoc rsp :body (s/->source body)))))))))))

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

(defn accept-encoding-value [accept-encoding]
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

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
    (when-let [found (when content-type
                       (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
      (second found))
    "UTF-8"))

(defn generate-query-string-with-encoding [params encoding]
  (str/join "&"
    (mapcat (fn [[k v]]
              (if (sequential? v)
                (map #(str (url-encode (name %1) encoding)
                        "="
                        (url-encode (str %2) encoding))
                  (repeat k) v)
                [(str (url-encode (name k) encoding)
                   "="
                   (url-encode (str v) encoding))]))
      params)))

(defn generate-query-string [params & [content-type]]
  (let [encoding (detect-charset content-type)]
    (generate-query-string-with-encoding params encoding)))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [{:keys [query-params content-type]
    :or {content-type :x-www-form-urlencoded}
    :as req}]
  (if query-params
    (-> req
      (dissoc :query-params)
      (update-in [:query-string]
        (fn [old-query-string new-query-string]
          (if-not (empty? old-query-string)
            (str old-query-string "&" new-query-string)
            new-query-string))
        (generate-query-string
          query-params
          (content-type-value content-type))))
    req))

(defn basic-auth-value
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

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [req]
  (if-let [[user password] (parse-user-info (:user-info req))]
    (assoc req :basic-auth [user password])
    req))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [req]
  (if-let [m (:method req)]
    (-> req
      (dissoc :method)
      (assoc :request-method m))
    req))

(defmulti coerce-form-params
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
             {:type :transit-not-loaded
              :form-params form-params
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
             {:type :cheshire-not-loaded
              :form-params form-params
              :json-opts json-opts})))
  (json-encode form-params json-opts))

(defmethod coerce-form-params :default [{:keys [content-type form-params
                                                form-param-encoding]}]
  (if form-param-encoding
    (generate-query-string-with-encoding form-params form-param-encoding)
    (generate-query-string form-params (content-type-value content-type))))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [{:keys [form-params content-type request-method]
    :or {content-type :x-www-form-urlencoded}
    :as req}]

  (if (and form-params (#{:post :put :patch} request-method))
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

(def default-middleware
  [wrap-method
   wrap-url
   wrap-nested-params
   wrap-query-params
   wrap-form-params
   wrap-user-info
   wrap-basic-auth
   wrap-oauth
   wrap-accept
   wrap-accept-encoding
   wrap-content-type])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [client]
  (let [client' (-> client
                  wrap-exceptions
                  wrap-request-timing)]
    (fn [req]
      (let [executor (ex/executor)]
        (if (:aleph.http.client/close req)
          (client req)

          (let [req' (reduce #(%2 %1) req default-middleware)]
            (d/chain' (client' req')

              ;; coerce the response body
              (fn [{:keys [body] :as rsp}]
                (if body
                  (d/future-with (or executor (ex/wait-pool))
                    (coerce-response-body req' rsp))
                  rsp)))))))))
