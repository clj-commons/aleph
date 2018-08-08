(ns aleph.http
  (:refer-clojure :exclude [get])
  (:require
    [clojure.string :as str]
    [manifold.deferred :as d]
    [manifold.executor :as executor]
    [aleph.flow :as flow]
    [aleph.http
     [server :as server]
     [client :as client]
     [client-middleware :as middleware]]
    [aleph.netty :as netty])
  (:import
    [io.aleph.dirigiste Pools]
    [aleph.utils
     PoolTimeoutException
     ConnectionTimeoutException
     RequestTimeoutException
     ReadTimeoutException]
    [java.net
     URI
     InetSocketAddress]
    [java.util.concurrent
     TimeoutException]))

(defn start-server
  "Starts an HTTP server using the provided Ring `handler`.  Returns a server object which can be stopped
   via `java.io.Closeable.close()`, and whose port can be discovered with `aleph.netty/port`.


   |:---------|:-------------
   | `port` | the port the server will bind to.  If `0`, the server will bind to a random port.
   | `socket-address` |  a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object if an SSL connection is desired |
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `executor` | a `java.util.concurrent.Executor` which is used to handle individual requests.  To avoid this indirection you may specify `:none`, but in this case extreme care must be taken to avoid blocking operations on the handler's thread.
   | `shutdown-executor?` | if `true`, the executor will be shut down when `.close()` is called on the server, defaults to `true`.
   | `request-buffer-size` | the maximum body size, in bytes, which the server will allow to accumulate before invoking the handler, defaults to `16384`.  This does *not* represent the maximum size request the server can handle (which is unbounded), and is only a means of maximizing performance.
   | `raw-stream?` | if `true`, bodies of requests will not be buffered at all, and will be represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream`.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `rejected-handler` | a spillover request-handler which is invoked when the executor's queue is full, and the request cannot be processed.  Defaults to a `503` response.
   | `max-initial-line-length` | the maximum characters that can be in the initial line of the request, defaults to `8192`
   | `max-header-size` | the maximum characters that can be in a single header entry of a request, defaults to `8192`
   | `max-chunk-size` | the maximum characters that can be in a single chunk of a streamed request, defaults to `16384`
   | `epoll?` | if `true`, uses `epoll` when available, defaults to `false`
   | `compression?` | when `true` enables http compression, defaults to `false`
   | `compression-level` | optional compression level, `1` yields the fastest compression and `9` yields the best compression, defaults to `6`. When set, enables http content compression regardless of the `compression?` flag value
   | `idle-timeout` | when set, forces keep-alive connections to be closed after an idle time, in milliseconds"
  [handler options]
  (server/start-server handler options))

(defn- create-connection
  "Returns a deferred that yields a function which, given an HTTP request, returns
   a deferred representing the HTTP response.  If the server disconnects, all responses
   will be errors, and a new connection must be created."
  [^URI uri options middleware on-closed]
  (let [scheme (.getScheme uri)
        ssl? (= "https" scheme)]
    (-> (client/http-connection
          (InetSocketAddress/createUnresolved
            (.getHost uri)
            (int
              (or
                (when (pos? (.getPort uri)) (.getPort uri))
                (if ssl? 443 80))))
          ssl?
          (if on-closed
            (assoc options :on-closed on-closed)
            options))

      (d/chain' middleware))))

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
  (flow/utilization-executor 0.9 256 {:onto? false}))

(defn connection-pool
  "Returns a connection pool which can be used as an argument in `request`.

   |:---|:---
   | `connections-per-host` | the maximum number of simultaneous connections to any host
   | `total-connections` | the maximum number of connections across all hosts
   | `target-utilization` | the target utilization of connections per host, within `[0,1]`, defaults to `0.9`
   | `stats-callback` | an optional callback which is invoked with a map of hosts onto usage statistics every ten seconds
   | `max-queue-size` | the maximum number of pending acquires from the pool that are allowed before `acquire` will start to throw a `java.util.concurrent.RejectedExecutionException`, defaults to `65536`
   | `control-period` | the interval, in milliseconds, between use of the controller to adjust the size of the pool, defaults to `60000`
   | `dns-options` | an optional map with async DNS resolver settings, for more information check `aleph.netty/dns-resolver-group`. When set, ignores `name-resolver` setting from `connection-options` in favor of shared DNS resolver instance
   | `middleware` | a function to modify request before sending, defaults to `aleph.http.client-middleware/wrap-request`

   the `connection-options` are a map describing behavior across all connections:

   |:---|:---
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object, only required if a custom context is required
   | `local-address` | an optional `java.net.SocketAddress` describing which local interface should be used
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.Bootstrap` object and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `insecure?` | if `true`, ignores the certificate for any `https://` domains
   | `response-buffer-size` | the amount of the response, in bytes, that is buffered before the request returns, defaults to `65536`.  This does *not* represent the maximum size response that the client can handle (which is unbounded), and is only a means of maximizing performance.
   | `keep-alive?` | if `true`, attempts to reuse connections for multiple requests, defaults to `true`.
   | `idle-timeout` | when set, forces keep-alive connections to be closed after an idle time, in milliseconds.
   | `epoll?` | if `true`, uses `epoll` when available, defaults to `false`
   | `raw-stream?` | if `true`, bodies of responses will not be buffered at all, and represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream`.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `max-initial-line-length` | the maximum length of the initial line (e.g. HTTP/1.0 200 OK), defaults to `65536`
   | `max-header-size` | the maximum characters that can be in a single header entry of a response, defaults to `65536`
   | `max-chunk-size` | the maximum characters that can be in a single chunk of a streamed response, defaults to `65536`
   | `name-resolver` | specify the mechanism to resolve the address of the unresolved named address. When not set or equals to `:default`, JDK's built-in domain name lookup mechanism is used (blocking). Set to`:noop` not to resolve addresses or pass an instance of `io.netty.resolver.AddressResolverGroup` you need. Note, that if the appropriate connection-pool is created with dns-options shared DNS resolver would be used
   | `proxy-options` | a map to specify proxy settings. HTTP, SOCKS4 and SOCKS5 proxies are supported. Note, that when using proxy `connections-per-host` configuration is still applied to the target host disregarding tunneling settings. If you need to limit number of connections to the proxy itself use `total-connections` setting.
   | `response-executor` | optional `java.util.concurrent.Executor` that will execute response callbacks
   | `log-activity` | when set, logs all events on each channel (connection) with a log level given. Accepts either one of `:trace`, `:debug`, `:info`, `:warn`, `:error` or an instance of `io.netty.handler.logging.LogLevel`. Note, that this setting *does not* enforce any changes to the logging configuration (default configuration is `INFO`, so you won't see any `DEBUG` or `TRACE` level messages, unless configured explicitly)

   Supported `proxy-options` are

   |:---|:---
   | `host` | host of the proxy server
   | `port` | an optional port to establish connection (defaults to 80 for http and 1080 for socks proxies)
   | `protocol` | one of `:http`, `:socks4` or `:socks5` (defaults to `:http`)
   | `user` | an optional auth username
   | `password` | an optional auth password
   | `http-headers` | (HTTP proxy only) an optional map to set additional HTTP headers when establishing connection to the proxy server
   | `tunnel?` | (HTTP proxy only) if `true`, sends HTTP CONNECT to the proxy and waits for the 'HTTP/1.1 200 OK' response before sending any subsequent requests. Defaults to `false`. When using authorization or specifying additional headers uses tunneling disregarding this setting
   | `connection-timeout` | timeout in milliseconds for the tunnel become established, defaults to 60 seconds, setting is ignored when tunneling is not used."
  [{:keys [connections-per-host
           total-connections
           target-utilization
           connection-options
           dns-options
           stats-callback
           control-period
           middleware
           max-queue-size]
    :or {connections-per-host 8
         total-connections 1024
         target-utilization 0.9
         control-period 60000
         middleware middleware/wrap-request
         max-queue-size 65536}}]
  (when (and (false? (:keep-alive? connection-options))
             (pos? (:idle-timeout connection-options 0)))
    (throw
     (IllegalArgumentException.
      ":idle-timeout option is not allowed when :keep-alive? is explicitly disabled")))

  (let [conn-options' (cond-> connection-options
                        (some? dns-options)
                        (assoc :name-resolver (netty/dns-resolver-group dns-options)))
        p (promise)
        pool (flow/instrumented-pool
               {:generate (fn [host]
                            (let [c (promise)
                                  conn (create-connection
                                         host
                                         conn-options'
                                         middleware
                                         #(flow/dispose @p host [@c]))]
                              (deliver c conn)
                              [conn]))
                :destroy (fn [_ c]
                           (d/chain' c
                             first
                             client/close-connection))
                :control-period control-period
                :max-queue-size max-queue-size
                :controller (Pools/utilizationController
                              target-utilization
                              connections-per-host
                              total-connections)
                :stats-callback stats-callback})]
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
   | `extensions?` | if `true`, the websocket extensions will be supported.
   | `sub-protocols` | a string with a comma seperated list of supported sub-protocols.
   | `headers` | the headers that should be included in the handshake
   | `compression?` | when set to `true`, enables client to use permessage-deflate compression extension, defaults to `false`.
   | `pipeline-transform` | an optional function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `max-frame-payload` | maximum allowable frame payload length, in bytes, defaults to `65536`.
   | `max-frame-size` | maximum aggregate message size, in bytes, defaults to `1048576`.
   | `bootstrap-transform` | an optional function that takes an `io.netty.bootstrap.Bootstrap` object and modifies it.
   | `epoll?` | if `true`, uses `epoll` when available, defaults to `false`"
  ([url]
    (websocket-client url nil))
  ([url options]
    (client/websocket-connection url options)))

(defn websocket-connection
  "Given an HTTP request that can be upgraded to a WebSocket connection, returns a
   deferred which yields a duplex stream that can be used to communicate with the
   client over the WebSocket protocol.

   |:---|:---
   | `raw-stream?` | if `true`, the connection will emit raw `io.netty.buffer.ByteBuf` objects rather than strings or byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `headers` | the headers that should be included in the handshake
   | `compression?` | when set to `true`, enables permessage-deflate compression extention support for the connection, defaults to `false`.
   | `pipeline-transform` | an optional function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `max-frame-payload` | maximum allowable frame payload length, in bytes, defaults to `65536`.
   | `max-frame-size` | maximum aggregate message size, in bytes, defaults to `1048576`.
   | `allow-extensions?` | if true, allows extensions to the WebSocket protocol, defaults to `false`"
  ([req]
    (websocket-connection req nil))
  ([req options]
    (server/initialize-websocket-handler req options)))

(let [maybe-timeout! (fn [d timeout] (when d (d/timeout! d timeout)))]
  (defn request
    "Takes an HTTP request, as defined by the Ring protocol, with the extensions defined
     by [clj-http](https://github.com/dakrone/clj-http), and returns a deferred representing
     the HTTP response.  Also allows for a custom `pool` or `middleware` to be defined.

     |:---|:---
     | `pool` | a custom connection pool
     | `middleware` | custom client middleware for the request
     | `pool-timeout` | timeout in milliseconds for the pool to generate a connection
     | `connection-timeout` | timeout in milliseconds for the connection to become established
     | `request-timeout` | timeout in milliseconds for the arrival of a response over the established connection
     | `read-timeout` | timeout in milliseconds for the response to be completed
     | `follow-redirects?` | whether to follow redirects, defaults to `true`; see `aleph.http.client-middleware/handle-redirects`"
    [{:keys [pool
             middleware
             pool-timeout
             response-executor
             connection-timeout
             request-timeout
             read-timeout
             follow-redirects?]
      :or {pool default-connection-pool
           response-executor default-response-executor
           middleware identity
           connection-timeout 6e4} ;; 60 seconds
      :as req}]

    (executor/with-executor response-executor
      ((middleware
         (fn [req]
           (let [k (client/req->domain req)
                 start (System/currentTimeMillis)]

             ;; acquire a connection
             (-> (flow/acquire pool k)
               (maybe-timeout! pool-timeout)

               ;; pool timeout triggered
               (d/catch' TimeoutException
                 (fn [^Throwable e]
                   (d/error-deferred (PoolTimeoutException. e))))

               (d/chain'
                 (fn [conn]

                   ;; get the wrapper for the connection, which may or may not be realized yet
                   (-> (first conn)

                     (maybe-timeout! connection-timeout)

                     ;; connection timeout triggered, dispose of the connetion
                     (d/catch' TimeoutException
                       (fn [^Throwable e]
                         (flow/dispose pool k conn)
                         (d/error-deferred (ConnectionTimeoutException. e))))

                     ;; connection failed, bail out
                     (d/catch'
                       (fn [e]
                         (flow/dispose pool k conn)
                         (d/error-deferred e)))

                     ;; actually make the request now
                     (d/chain'

                       (fn [conn']

                         (when-not (nil? conn')
                           (let [end (System/currentTimeMillis)]
                             (-> (conn' req)
                               (maybe-timeout! request-timeout)

                               ;; request timeout triggered, dispose of the connection
                               (d/catch' TimeoutException
                                 (fn [^Throwable e]
                                   (flow/dispose pool k conn)
                                   (d/error-deferred (RequestTimeoutException. e))))

                               ;; request failed, dispose of the connection
                               (d/catch'
                                 (fn [e]
                                   (flow/dispose pool k conn)
                                   (d/error-deferred e)))

                               ;; clean up the response
                               (d/chain'
                                 (fn [rsp]

                                   ;; only release the connection back once the response is complete
                                   (-> (:aleph/complete rsp)
                                     (maybe-timeout! read-timeout)

                                     (d/catch' TimeoutException
                                       (fn [^Throwable e]
                                         (flow/dispose pool k conn)
                                         (d/error-deferred (ReadTimeoutException. e))))

                                     (d/chain'
                                       (fn [early?]
                                         (if (or early?
                                               (not (:aleph/keep-alive? rsp))
                                               (<= 400 (:status rsp)))
                                           (flow/dispose pool k conn)
                                           (flow/release pool k conn)))))
                                   (-> rsp
                                     (dissoc :aleph/complete)
                                     (assoc :connection-time (- end start)))))))))

                       (fn [rsp]
                         (->> rsp
                           (middleware/handle-cookies req)
                           (middleware/handle-redirects request req)))))))))))
        req))))

(defn- req
  ([method url]
    (req method url nil))
  ([method url options]
    (request
      (assoc options
        :request-method method
        :url url))))

(def ^:private arglists
  '[[url]
    [url
     {:keys [pool middleware headers body multipart]
      :or {pool default-connection-pool
           middleware identity}
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
   | `body` | an optional body, which should be coercable to a byte representation via [byte-streams](https://github.com/ztellman/byte-streams)
   | `multipart` | a vector of bodies")
       :arglists arglists)))

(def-http-method get)
(def-http-method post)
(def-http-method put)
(def-http-method patch)
(def-http-method options)
(def-http-method trace)
(def-http-method head)
(def-http-method delete)
(def-http-method connect)

(defn get-all
  "Given a header map from an HTTP request or response, returns a collection of values associated with the key,
   rather than a comma-delimited string."
  [^aleph.http.core.HeaderMap headers ^String k]
  (-> headers ^io.netty.handler.codec.http.HttpHeaders (.headers) (.getAll k)))
