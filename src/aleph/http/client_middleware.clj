(ns aleph.http.client-middleware
  "This middleware is adapted from clj-http, whose license is amendable to this sort of
   copy/pastery"
  (:require [clojure.stacktrace :refer [root-cause]]
            [clojure.string :as str]
            [clojure.walk :refer [prewalk]]
            [manifold.deferred :as d])
  (:import (java.io InputStream)
           (java.net URL URLEncoder UnknownHostException)))

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

(defn url-encode [s]
  (URLEncoder/encode s "UTF-8"))

(defn opt
  "Check the request parameters for a keyword  boolean option, with or without
  the ?

  Returns false if either of the values are false, or the value of
  (or key1 key2) otherwise (truthy)"
  [req param]
  (let [param-? (keyword (str (name param) "?"))
        v1 (clojure.core/get req param)
        v2 (clojure.core/get req param-?)]
    (if (false? v1)
      false
      (if (false? v2)
        false
        (or v1 v2)))))

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

(defn wrap-exceptions
  "Middleware that throws a slingshot exception if the response is not a
  regular response. If :throw-entire-message? is set to true, the entire
  response is used as the message, instead of just the status number."
  [client]
  (fn [req]
    (d/chain (client req)
      (fn [{:keys [status] :as resp}]
        (if (unexceptional-status? status)
          resp
          (if (false? (opt req :throw-exceptions))
            resp
            (d/error-deferred (ex-info (str "status " status) resp))))))))

(declare wrap-redirects)

(defn follow-redirect
  "Attempts to follow the redirects from the \"location\" header, if no such
  header exists (bad server!), returns the response without following the
  request."
  [client {:keys [uri url scheme server-name server-port] :as req}
   {:keys [trace-redirects ^InputStream body] :as resp}]
  (let [url (or url (str (name scheme) "://" server-name
                         (when server-port (str ":" server-port)) uri))]
    (if-let [raw-redirect (get-in resp [:headers "location"])]
      (let [redirect (str (URL. (URL. url) raw-redirect))]
        (try (.close body) (catch Exception _))
        ((wrap-redirects client) (-> req
                                     (merge (parse-url redirect))
                                     (dissoc :query-params)
                                     (assoc :url redirect)
                                     (assoc :trace-redirects trace-redirects))))
      ;; Oh well, we tried, but if no location is set, return the response
      resp)))

(defn wrap-redirects
  "Middleware that follows redirects in the response. A slingshot exception is
  thrown if too many redirects occur. Options

  :follow-redirects - default:true, whether to follow redirects
  :max-redirects - default:20, maximum number of redirects to follow
  :force-redirects - default:false, force redirecting methods to GET requests

  In the response:

  :redirects-count - number of redirects
  :trace-redirects - vector of sites the request was redirected from"
  [client]
  (fn [{:keys [request-method max-redirects redirects-count trace-redirects url]
        :or {redirects-count 1 trace-redirects []
             ;; max-redirects default taken from Firefox
             max-redirects 20}
        :as req}]
    (let [{:keys [status] :as resp} (client req)
          resp-r (assoc resp :trace-redirects
                        (if url
                          (conj trace-redirects url)
                          trace-redirects))]
      (cond
       (false? (opt req :follow-redirects))
       resp
       (not (redirect? resp-r))
       resp-r
       (and max-redirects (> redirects-count max-redirects))
       (if (opt req :throw-exceptions)
         (throw (IllegalStateException. (str "Too many redirects: " redirects-count)))
         resp-r)
       (= 303 status)
       (follow-redirect client (assoc req :request-method :get
                                      :redirects-count (inc redirects-count))
                        resp-r)
       (#{301 302} status)
       (cond
        (#{:get :head} request-method)
        (follow-redirect client (assoc req :redirects-count
                                       (inc redirects-count)) resp-r)
        (opt req :force-redirects)
        (follow-redirect client (assoc req
                                  :request-method :get
                                  :redirects-count (inc redirects-count))
                         resp-r)
        :else
        resp-r)
       (= 307 status)
       (if (or (#{:get :head} request-method)
               (opt req :force-redirects))
         (follow-redirect client (assoc req :redirects-count
                                        (inc redirects-count)) resp-r)
         resp-r)
       :else
       resp-r))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [client]
  (fn [{:keys [content-type character-encoding] :as req}]
    (if content-type
      (let [ctv (content-type-value content-type)
            ct (if character-encoding
                 (str ctv "; charset=" character-encoding)
                 ctv)]
        (client (update-in req [:headers] assoc "content-type" ct)))
      (client req))))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                  (assoc-in [:headers "accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                  (assoc-in [:headers "accept-encoding"]
                            (accept-encoding-value accept-encoding))))
      (client req))))

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
   (when-let [found (when content-type
                      (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
     (second found))
   "UTF-8"))

(defn generate-query-string [params & [content-type]]
  (let [encoding (detect-charset content-type)]
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
                      params))))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [client]
  (fn [{:keys [query-params content-type]
        :or {content-type :x-www-form-urlencoded}
        :as req}]
    (if query-params
      (client (-> req (dissoc :query-params)
                  (update-in [:query-string]
                             (fn [old-query-string new-query-string]
                               (if-not (empty? old-query-string)
                                 (str old-query-string "&" new-query-string)
                                 new-query-string))
                             (generate-query-string
                              query-params
                              (content-type-value content-type)))))
      (client req))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))
        bytes (.getBytes ^String basic-auth "UTF-8")]
    (str "Basic "
      (-> ^String basic-auth
        (.getBytes "UTF-8")
        javax.xml.bind.DatatypeConverter/printBase64Binary))))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                  (assoc-in [:headers "authorization"]
                            (basic-auth-value basic-auth))))
      (client req))))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [client]
  (fn [req]
    (if-let [oauth-token (:oauth-token req)]
      (client (-> req (dissoc :oauth-token)
                  (assoc-in [:headers "authorization"]
                            (str "Bearer " oauth-token))))
      (client req))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                  (assoc :request-method m)))
      (client req))))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [client]
  (fn [{:keys [form-params content-type request-method json-opts]
        :or {content-type :x-www-form-urlencoded}
        :as req}]
    (if (and form-params (#{:post :put :patch} request-method))
      (client (-> req
                  (dissoc :form-params)
                  (assoc :content-type (content-type-value content-type)
                         :body (generate-query-string form-params (content-type-value content-type)))))
      (client req))))

(defn- nest-params
  [request param-key]
  (if-let [params (request param-key)]
    (assoc request param-key (prewalk
                              #(if (and (vector? %) (map? (second %)))
                                 (let [[fk m] %]
                                   (reduce
                                    (fn [m [sk v]]
                                      (assoc m (str (name fk)
                                                    \[ (name sk) \]) v))
                                    {}
                                    m))
                                 %)
                              params))
    request))

(defn wrap-nested-params
  "Middleware wrapping nested parameters for query strings."
  [client]
  (fn [{:keys [query-params form-params content-type] :as req}]
    (if (= :json content-type)
      (client req)
      (client (reduce
               nest-params
               req
               [:query-params :form-params])))))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(defn wrap-unknown-host
  "Middleware ignoring unknown hosts when the :ignore-unknown-host? option
  is set."
  [client]
  (fn [req]
    (try
      (client req)
      (catch Exception e
        (if (= (type (root-cause e)) UnknownHostException)
          (when-not (opt req :ignore-unknown-host)
            (throw (root-cause e)))
          (throw (root-cause e)))))))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in milliseconds)
  of the request into the :request-time key in the response."
  [client]
  (fn [req]
    (let [start (System/currentTimeMillis)]
      (d/chain (client req)
        #(assoc % :request-time (- (System/currentTimeMillis) start))))))

(def default-middleware
  "The default list of middleware clj-http uses for wrapping requests."
  [wrap-request-timing
   wrap-query-params
   wrap-basic-auth
   wrap-oauth
   wrap-user-info
   wrap-url
   ;; wrap-redirects
   ;; wrap-exceptions
   wrap-accept
   wrap-accept-encoding
   wrap-content-type
   wrap-form-params
   wrap-nested-params
   wrap-method
   wrap-unknown-host])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [request]
  (reduce
    (fn wrap-request* [request middleware]
      (middleware request))
    request
    default-middleware))
