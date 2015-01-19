(ns aleph.http
  (:refer-clojure :exclude [get])
  (:require
    [manifold.deferred :as d]
    [aleph.flow :as flow]
    [aleph.http
     [server :as server]
     [client :as client]
     [client-middleware :as middleware]])
  (:import
    [io.aleph.dirigiste Pools]
    [java.net URI]))

(defn start-server
  "Starts an HTTP server using the provided Ring `handler`."
  [handler
   {:keys [port executor raw-stream? bootstrap-transform ssl-context]
    :as options}]
  (server/start-server handler options))

(defn- create-connection
  "Returns a deferred that yields a function which, given an HTTP request, returns
   a deferred representing the HTTP response.  If the server disconnects, all responses
   will be errors, and a new connection must be created."
  [^URI uri options on-closed]
  (let [scheme (.getScheme uri)
        ssl? (= "https" scheme)]
    (d/chain
      (client/http-connection
        (.getHost uri)
        (or
          (when (pos? (.getPort uri)) (.getPort uri))
          (if ssl? 443 80))
        ssl?
        (if on-closed
          (assoc options :on-closed on-closed)
          options))
      middleware/wrap-request)))

(def ^:private connection-stats-callbacks (atom #{}))

(defn register-connection-stats-callback
  "Registers a callback which will be called with connection-pool stats."
  [c]
  (swap! connection-stats-callbacks conj c))

(defn unregister-connection-stats-callback
  "Unregisters a previous connection-pool stats callback."
  [c]
  (swap! connection-stats-callbacks disj c))

(defn connection-pool
  "Returns a connection pool which can be sued "
  [{:keys [connections-per-host
           total-connections
           target-utilization
           options
           stats-callback]
    :or {connections-per-host 8
         total-connections 1024
         middleware identity
         target-utilization 0.9}}]
  (let [p (promise)
        pool (flow/instrumented-pool
               {:generate (fn [host]
                            (let [c (promise)
                                  conn (create-connection host options #(flow/dispose @p host [@c]))]
                              (deliver c conn)
                              [conn]))
                :destroy (fn [_ c]
                           (d/chain c
                             first
                             client/close-connection))
                :controller (Pools/utilizationController target-utilization connections-per-host total-connections)
                :stats-callback stats-callback
                })]
    @(deliver p pool)))

(def default-connection-pool
  (connection-pool
    {:stats-callback
     (fn [s]
       (doseq [c @connection-stats-callbacks]
         (c s)))}))

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
  ([req]
     (request default-connection-pool req identity))
  ([req middleware]
     (request default-connection-pool req middleware))
  ([connection-pool req middleware]
     (let [k (client/req->domain req)
           start (System/currentTimeMillis)]
       (d/chain (flow/acquire connection-pool k)
         (fn [conn]
           (let [end (System/currentTimeMillis)]
             (-> (first conn)
               (d/chain
                 (fn [conn']
                   (let [end (System/currentTimeMillis)]
                     (-> (middleware conn')
                       (d/chain
                         #(% req)
                         #(assoc % :connection-time (- end start)))))))
               (d/finally #(flow/release connection-pool k conn)))))))))

(defn- req
  ([method url]
     (req method url nil))
  ([method url
    {:keys [pool middleware]
     :or {pool default-connection-pool
          middleware identity}
     :as options}]
     (let [start (System/currentTimeMillis)
           req (assoc options
                 :request-method method
                 :url url)]
       (request pool req middleware))))

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
