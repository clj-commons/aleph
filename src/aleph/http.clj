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
  "Starts an HTTP server using the provided Ring `handler`."
  [handler
   {:keys [port executor raw-stream? bootstrap-transform ssl-context]
    :as options}]
  (server/start-server handler options))

(defn close-http-connection
  "Given a function returned by `http-connection`, closes it."
  [conn]
  (client/close-connection conn))

(defn http-connection
  "Returns a deferred that yields a function which, given an HTTP request, returns
   a deferred representing the HTTP response.  If the server disconnects, all responses
   will be errors, and a new connection must be created."
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

(defn websocket-client
  "Given a url, returns a deferred which yields a duplex stream that can be used to
   communicate with a server over the WebSocket protocol."
  ([url]
     (websocket-client url nil))
  ([url {:keys [raw-stream? insecure? headers] :as options}]
     (client/websocket-connection url options)))

(defn websocket-connection
  "Given an HTTP request that can be upgraded to a WebSocket connection, returns a
   deferred which yields a duplex stream that can be used to communicate with the
   client over the WebSocket protocol."
  ([req]
     (websocket-connection req nil))
  ([req {:keys [raw-stream? headers] :as options}]
     (server/initialize-websocket-handler req options)))

(defn request
  "Takes an HTTP request, as defined by the Ring protocol, with the extensions defined
   by `clj-http`, and returns a deferred representing the HTTP response."
  [req]
  (d/chain (http-connection (assoc req :keep-alive? false))
    #(% req)))

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
