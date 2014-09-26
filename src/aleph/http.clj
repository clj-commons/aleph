(ns aleph.http
  (:refer-clojure :exclude [get])
  (:require
    [manifold.deferred :as d]
    [aleph.http
     [server :as server]
     [client :as client]
     [client-middleware :as middleware]])
  (:import
    [java.net URI]))

(defn start-server
  [handler
   {:keys [port executor raw-stream? bootstrap-transform ssl-context]
    :as options}]
  (server/start-server handler options))

(defn close-connection
  [conn]
  (client/close-connection conn))

(defn http-connection
  [{:keys [url host port scheme] :as options}]
  (let [^URI uri (when url (URI. url))
        scheme (or scheme (when uri (.getScheme uri)) "http")
        ssl? (= "https" scheme)]
    (d/chain
      (client/http-connection
        (or host (when uri (.getHost uri)))
        (or port
          (when (and uri (pos? (.getPort uri))) (.getPort uri))
          (if ssl? 443 80))
        ssl?
        options)
      middleware/wrap-request)))

(defn request
  [req]
  (d/chain (http-connection req)
    #(% (assoc req :keep-alive? false))))

(defn- req
  ([method url]
     (req method url nil))
  ([method url {:keys [connection] :as options}]
     (let [start (System/currentTimeMillis)
           req (assoc options
                 :request-method method
                 :url url)]
       (d/chain
         (if connection
           (if (d/deferred? connection)
             (d/chain connection #(% req))
             (connection req))
           (request req))
         (fn [rsp]
           (assoc rsp
             :connection-time (- (System/currentTimeMillis) start)))))))

(def get
  "Makes a GET request, returns a deferred representing the response."
  (partial req :get))

(def post
  "Makes a POST request, returns a deferred representing the response."
  (partial req :post))

(def put
  "Makes a PUT request, returns a deferred representing the response."
  (partial req :put))

(def options
  "Makes a OPTIONS request, returns a deferred representing the response."
  (partial req :options))

(def trace
  "Makes a TRACE request, returns a deferred representing the response."
  (partial req :trace))

(def head
  "Makes a GET request, returns a deferred representing the response."
  (partial req :head))

(def delete
  "Makes a DELETE request, returns a deferred representing the response."
  (partial req :delete))

(def connect
  "Makes a CONNECT request, returns a deferred representing the response."
  (partial req :connect))
