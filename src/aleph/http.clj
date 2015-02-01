(ns aleph.http
  (:refer-clojure :exclude [get])
  (:require
    [clojure.string :as str]
    [manifold.deferred :as d]
    [aleph.flow :as flow]
    [aleph.http
     [server :as server]
     [client :as client]
     [client-middleware :as middleware]])
  (:import
    [io.aleph.dirigiste Pools]
    [java.net
     URI
     InetSocketAddress]
    [java.util.concurrent
     TimeoutException]))

(defn start-server
  "Starts an HTTP server using the provided Ring `handler`.  Returns a server object which can be stopped
   via `java.io.Closeable.close()`, and whose port can be discovered with `aleph.netty/port`.


   |:---------|:-------------
   | `port` | the port the server will bind to.  If `-1`, the server will bind to a random port.
   | `socket-address` |  a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object. If a self-signed certificate is all that's required, `(aleph.netty/self-signed-ssl-context)` will suffice.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `executor` | a `java.util.concurrent.Executor` which is used to handle individual requests.  To avoid this indirection you may specify `:none`, but in this case extreme care must be taken to avoid blocking operations on the handler's thread.
   | `shutdown-executor?` | if `true`, the executor will be shut down when `.close()` is called on the server, defaults to `true`.
   | `request-buffer-size` | the maximum body size, in bytes, which the server will allow to accumulate before invoking the handler, defaults to `16384`.  This does *not* represent the maximum size request the server can handle (which is unbounded), and is only a means of maximizing performance.
   | `raw-stream?` | if `true`, bodies of requests will not be buffered at all, and will be represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream`.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `rejected-handler` | a spillover request-handler which is invoked when the executor's queue is full, and the request cannot be processed.  Defaults to a `503` response.
  | `max-initial-line-length` | the maximum characters that can be in the initial line of the request, defaults to `4096`
  | `max-header-size` | the maximum characters that can be in a single header entry of a request, defaults to `8192`
  | `max-chunk-size` | the maximum characters that can be in a single chunk of a streamed request, defaults to `8192`"
  [handler
   {:keys [port socket-address executor raw-stream? bootstrap-transform pipeline-transform ssl-context request-buffer-size shutdown-executor? rejected-handler]
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
        (InetSocketAddress.
          (.getHost uri)
          (int
            (or
              (when (pos? (.getPort uri)) (.getPort uri))
              (if ssl? 443 80))))
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

(def default-response-executor
  (flow/utilization-executor 0.9 256))

(defn connection-pool
  "Returns a connection pool which can be used as an argument in `request`.

   |:---|:---
   | `connections-per-host` | the maximum number of simultaneous connections to any host
   | `total-connections` | the maximum number of connections across all hosts
   | `target-utilization` | the target utilization of connections per host, within `[0,1]`, defaults to `0.9`
   | `stats-callback` | an optional callback which is invoked with a map of hosts onto usage statistics every ten seconds

   the `connection-options` are a map describing behavior across all connections:

   |:---|:---
   | `local-address` | an optional `java.net.SocketAddress` describing which local interface should be used
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `insecure?` | if `true`, ignores the certificate for any `https://` domains
   | `response-buffer-size` | the amount of the response, in bytes, that is buffered before the request returns, defaults to `65536`.  This does *not* represent the maximum size response that the client can handle (which is unbounded), and is only a means of maximizing performance.
   | `keep-alive?` | if `true`, attempts to reuse connections for multiple requests, defaults to `true`.
   | `raw-stream?` | if `true`, bodies of respnoses will not be buffered at all, and represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream`.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
  | `max-header-size` | the maximum characters that can be in a single header entry of a response, defaults to `8192`
  | `max-chunk-size` | the maximum characters that can be in a single chunk of a streamed response, defaults to `8192`"
  [{:keys [connections-per-host
           total-connections
           target-utilization
           connection-options
           stats-callback
           response-executor]
    :or {connections-per-host 8
         total-connections 1024
         target-utilization 0.9
         response-executor default-response-executor}}]
  (let [p (promise)
        connection-options (assoc connection-options
                             :response-executor response-executor)
        pool (flow/instrumented-pool
               {:generate (fn [host]
                            (let [c (promise)
                                  conn (create-connection
                                         host
                                         connection-options
                                         #(flow/dispose @p host [@c]))]
                              (deliver c conn)
                              [conn]))
                :destroy (fn [_ c]
                           (d/chain c
                             first
                             client/close-connection))
                :controller (Pools/utilizationController
                              target-utilization
                              connections-per-host
                              total-connections)
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
   communicate with a server over the WebSocket protocol.

   |:---|:---
   | `raw-stream?` | if `true`, the connection will emit raw `io.netty.buffer.ByteBuf` objects rather than strings or byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `insecure?` | if `true`, the certificates for `wss://` will be ignored.
   | `headers` | the headers that should be included in the handshake"
  ([url]
     (websocket-client url nil))
  ([url {:keys [raw-stream? insecure? headers] :as options}]
     (client/websocket-connection url options)))

(defn websocket-connection
  "Given an HTTP request that can be upgraded to a WebSocket connection, returns a
   deferred which yields a duplex stream that can be used to communicate with the
   client over the WebSocket protocol.

   |:---|:---
   | `raw-stream?` | if `true`, the connection will emit raw `io.netty.buffer.ByteBuf` objects rather than strings or byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `headers` | the headers that should be included in the handshake"
  ([req]
     (websocket-connection req nil))
  ([req {:keys [raw-stream? headers] :as options}]
     (server/initialize-websocket-handler req options)))

(defn request
  "Takes an HTTP request, as defined by the Ring protocol, with the extensions defined
   by [clj-http](https://github.com/dakrone/clj-http), and returns a deferred representing
   the HTTP response.  Also allows for a custom `pool` or `middleware` to be defined.

   |:---|:---
   | `pool` | a custom connection pool
   | `middleware` | custom client middleware for the request
   | `socket-timeout` | timeout in milliseconds for the pool to allocate a socket
   | `connection-timeout` | timeout in milliseconds for the connection to become established
   | `request-timeout` | timeout in milliseconds for the arrival of a response over the established connection"
  [{:keys [pool
           middleware
           socket-timeout
           connection-timeout
           request-timeout]
    :or {pool default-connection-pool
         middleware identity}
    :as req}]
  (let [k (client/req->domain req)
        start (System/currentTimeMillis)
        maybe-timeout! (fn [d timeout]
                         (if timeout
                           (d/timeout! d timeout)
                           d))]
    (-> (flow/acquire pool k)
      (maybe-timeout! socket-timeout)
      (d/chain
        (fn [conn]
          (let [socket-end (System/currentTimeMillis)
                socket-time (- socket-end start)]
            (-> (first conn)
              (maybe-timeout! connection-timeout)
              (d/catch TimeoutException
                       #(do (flow/dispose pool k conn) (throw %)))
              (d/chain
                (fn [conn']
                  (let [connection-end (System/currentTimeMillis)
                        connection-time (- connection-end socket-end)]
                    (-> (middleware conn')
                      (d/chain #(% req))
                      (maybe-timeout! request-timeout)
                      (d/catch #(do (flow/dispose pool k conn) (throw %)))
                      (d/chain
                        (fn [rsp]
                          (d/chain (:aleph/complete rsp)
                            (fn [_]
                              (flow/release pool k conn)))
                          (-> rsp
                            (dissoc :aleph/complete)
                            (assoc :connection-time connection-time
                                   :socket-time socket-time)))))))))))))))

(defn- req
  ([method url]
     (req method url nil))
  ([method url options]
     (let [req (assoc options
                 :request-method method
                 :url url)]
       (request
         (merge
           {:url url
            :request-method method}
           options)))))

(def ^:private arglists
  '[[url]
    [url
     {:keys [pool middleware headers body]
      :or {pool default-connection-pool middleware identity}
      :as options}]])

(defmacro ^:private def-http-method [method]
  `(do
     (def ~method (partial req ~(keyword method)))
     (alter-meta! (resolve '~method) assoc
       :doc ~(str "Makes a " (str/upper-case (str method)) " request, returns a deferred representing
   the response.

   |:---|:---
   | `pool` | the `connection-pool` that should be used, defaults to the `default-connection-pool`
   | `middleware` | any additional middleware that should be used for handling requests and responses
   | `headers` | the HTTP headers for the request
   | `body` | an optional body, which should be coercable to a byte representation via [byte-streams](https://github.com/ztellman/byte-streams)")
       :arglists arglists)))

(def-http-method get)
(def-http-method post)
(def-http-method put)
(def-http-method options)
(def-http-method trace)
(def-http-method head)
(def-http-method delete)
(def-http-method connect)
