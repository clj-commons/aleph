(ns aleph.http
  (:refer-clojure :exclude [get])
  (:require
    [aleph.flow :as flow]
    [aleph.http.client :as client]
    [aleph.http.client-middleware :as middleware]
    [aleph.http.common :as common]
    [aleph.http.file :as file]
    [aleph.http.server :as server]
    [aleph.http.websocket.client :as ws.client]
    [aleph.http.websocket.common :as ws.common]
    [aleph.http.websocket.server :as ws.server]
    [aleph.netty :as netty]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.executor :as executor])
  (:import
    (aleph.utils
      ConnectionTimeoutException
      PoolTimeoutException
      ProxyConnectionTimeoutException
      ReadTimeoutException
      RequestCancellationException
      RequestTimeoutException)
    (io.aleph.dirigiste Pools)
    (io.netty.channel ConnectTimeoutException)
    (io.netty.handler.codec Headers)
    (io.netty.handler.codec.http HttpHeaders)
    (java.net
      InetSocketAddress
      URI)
    (java.util.concurrent TimeoutException)))

(defn start-server
  "Starts an HTTP server using the provided Ring `handler`.  Returns a server
   object which can be stopped via `java.io.Closeable.close()`, and whose port
   can be discovered with `aleph.netty/port` if not set.

   Defaults to HTTP/1.1-only.

   To enable HTTP/2, add `:http2` to the `http-versions` option. This requires you to also enable
   SSL via the `ssl-context` option. If you supply an `io.netty.handler.ssl.SslContext` instance, you
   have to set it up with ALPN support for HTTP/2.  See `aleph.netty/ssl-server-context` and
   `aleph.netty/application-protocol-config` for more details. If you supply an SSL options map
   without an `:application-protocol-config` key instead, the necessary ALPN configuration will be
   set up automatically. (You can also set `use-h2c?` to force HTTP/2 cleartext, but this is strongly
   discouraged.)

   | Param key                         | Description |
   | ---                               | --- |
   | `port`                            | The port the server will bind to. If `0`, the server will bind to a random port. |
   | `socket-address`                  | A `java.net.SocketAddress` specifying both the port and interface to bind to. |
   | `bootstrap-transform`             | A function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it. |
   | `http-versions`                   | An optional vector of allowable HTTP versions to negotiate via ALPN, in preference order. Defaults to `[:http1]`. |
   | `ssl-context`                     | An `io.netty.handler.ssl.SslContext` object or a map of SSL context options (see `aleph.netty/ssl-server-context` for more details) if an SSL connection is desired. When passing an `io.netty.handler.ssl.SslContext` object, it must have an ALPN config matching the `http-versions` option (see `aleph.netty/ssl-server-context` and `aleph.netty/application-protocol-config`). If only HTTP/1.1 is desired, ALPN config is optional.
   | `manual-ssl?`                     | Set to `true` to indicate that SSL is active, but the caller is managing it (this implies `:ssl-context` is nil). For example, this can be used if you want to use configure SNI (perhaps in `:pipeline-transform` ) to select the SSL context based on the client's indicated host name. |
   | `executor`                        | A `java.util.concurrent.Executor` which is used to handle individual requests. To avoid this indirection you may specify `:none`, but in this case extreme care must be taken to avoid blocking operations on the handler's thread. |
   | `shutdown-executor?`              | If `true`, the executor will be shut down when `.close()` is called on the server, defaults to `true` . |
   | `request-buffer-size`             | The maximum body size, in bytes, that the server will allow to accumulate before placing on the body stream, defaults to `16384` . This does *not* represent the maximum size request the server can handle (which is unbounded), and is only a means of maximizing performance. |
   | `raw-stream?`                     | If `true`, bodies of requests will not be buffered at all, and will be represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream` . This will minimize copying, but means that care must be taken with Netty's buffer reference counting. Only recommended for advanced users. |
   | `rejected-handler`                | A spillover request-handler which is invoked when the executor's queue is full, and the request cannot be processed. Defaults to a `503` response. |
   | `max-request-body-size`           | The maximum length of the request body in bytes. If set, requires queuing up content until the request is finished, and thus delays processing. Unlimited by default. |
   | `validate-headers`                | If `true`, validates the headers when decoding the request, defaults to `false` |
   | `transport`                       | The transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio` ) |
   | `compression?`                    | When `true`, enables HTTP body compression, defaults to `false` |
   | `compression-options`             | An optional Java array of `io.netty.handler.codec.compression.CompressionOptions`. If supplied, any codec options not passed in will be unavailable. Otherwise, uses default options for all available codecs. For Brotli/Zstd, be sure to guard your use with their `.isAvailable()` methods and add their deps to your classpath. |
   | `idle-timeout`                    | When set, connections are closed after not having performed any I/O operations for the given duration, in milliseconds. Defaults to `0` (infinite idle time). |
   | `continue-handler`                | Optional handler which is invoked when header sends \"Except: 100-continue\" header to test whether the request should be accepted or rejected. Handler should return `true`, `false`, ring responseo to be used as a reject response or deferred that yields one of those. |
   | `continue-executor`               | Optional `java.util.concurrent.Executor` which is used to handle requests passed to :continue-handler. To avoid this indirection you may specify `:none`, but in this case extreme care must be taken to avoid blocking operations on the handler's thread. |
   | `shutdown-timeout`                | Interval in seconds within which in-flight requests must be processed, defaults to 15 seconds. A value of `0` bypasses waiting entirely. |

   HTTP/1-specific options
   | Param key                         | Description |
   | ---                               | --- |
   | `allow-duplicate-content-lengths` | If `true`, allows duplicate `Content-Length` headers, defaults to true. Always true for HTTP/2 |
   | `max-initial-line-length`         | The maximum characters that can be in the initial line of the request, defaults to `8192` |
   | `max-header-size`                 | The maximum characters that can be in a single header entry of a request, defaults to `8192` |
   | `max-chunk-size`                  | The maximum characters that can be in a single chunk of a streamed request, defaults to `16384` |
   | `initial-buffer-size`             | The initial buffer size of characters when decoding the request, defaults to `128` |
   | `pipeline-transform`              | (DEPRECATED: Use `:http1-pipeline-transform` instead.) A function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it. |
   | `http1-pipeline-transform`        | A function that takes an `io.netty.channel.ChannelPipeline` object, which represents an HTTP/1 connection, and modifies it. Contains the user handler at \"request-handler\". |
   | `compression-level`               | (DEPRECATED: Use `compression-options` or leave unset.) Optional gzip/deflate compression level, `1` yields the fastest compression and `9` yields the best compression, defaults to `6`. Zstd or Brotli will also be enabled when available. Overridden by `compression-options`. |

   HTTP/2-specific options
   | Param key                         | Description |
   | ---                               | --- |
   | `use-h2c?`                        | If `true`, uses HTTP/2 for insecure servers. Has no effect on secure servers, and upgrades are not allowed. Defaults to false. |
   | `conn-go-away-handler`            | A connection-level cleanup handler for when a GOAWAY is received. Indicates the peer has, or soon will, close the TCP connection. Contains the last-processed stream ID.|
   | `stream-go-away-handler`          | A stream-level cleanup handler called for streams above the last-stream-id in a GOAWAY frame. Indicates the stream will not be processed. Called with the context and the Http2GoAwayFrame. |
   | `reset-stream-handler`            | A stream-level cleanup handler called for streams that have been sent RST_STREAM. Called with the context and the Http2ResetFrame. |
   | `http2-conn-pipeline-transform`   | A function that takes an `io.netty.channel.ChannelPipeline` object, which represents an HTTP/2 connection, and modifies it. |
   | `http2-stream-pipeline-transform` | A function that takes an `io.netty.channel.ChannelPipeline` object, which represents a single HTTP/2 stream, and modifies it. Contains the user handler at \"handler\". |
   "
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

(def ^:no-doc default-response-executor
  (flow/utilization-executor 0.9 256 {:onto? false}))

(defn connection-pool
  "Returns a connection pool which can be used as an argument in `request`.

   To enable HTTP/2, add `:http2` to the `http-versions` option. If you supply an
   `io.netty.handler.ssl.SslContext` instance for `:ssl-context`, you have to set it up with ALPN
   support for HTTP/2, as well. See `aleph.netty/ssl-client-context` and
   `aleph.netty/application-protocol-config` for more details.  If you supply an SSL options map
   without an `:application-protocol-config` key instead, the necessary ALPN configuration will be
   set up automatically. (You can also set `force-h2c?` to force HTTP/2 cleartext, but this is
   strongly discouraged.)

   Param key                      | Description
   | ---                          | ---
   | `connections-per-host`       | the maximum number of simultaneous connections to any host
   | `total-connections`          | the maximum number of connections across all hosts
   | `target-utilization`         | the target utilization of connections per host, within `[0,1]`, defaults to `0.9`
   | `stats-callback`             | an optional callback which is invoked with a map of hosts onto usage statistics every ten seconds
   | `max-queue-size`             | the maximum number of pending acquires from the pool that are allowed before `acquire` will start to throw a `java.util.concurrent.RejectedExecutionException`, defaults to `65536`
   | `control-period`             | the interval, in milliseconds, between use of the controller to adjust the size of the pool, defaults to `60000`
   | `dns-options`                | an optional map with async DNS resolver settings, for more information check `aleph.netty/dns-resolver-group`. When set, ignores `name-resolver` setting from `connection-options` in favor of shared DNS resolver instance
   | `middleware`                 | a function to modify request before sending, defaults to `aleph.http.client-middleware/wrap-request`
   | `pool-builder-fn`            | an optional 1-ary function which returns a `io.aleph.dirigiste.IPool` from a map containing the following keys: `generate`, `destroy`, `control-period`, `max-queue-size` and `stats-callback`.
   | `pool-controller-builder-fn` | an optional 0-ary function which returns a `io.aleph.dirigiste.IPool$Controller`.

   the `connection-options` are a map describing behavior across all connections:

   Param key                   | Description
   | ---                       | ---
   | `ssl-context`             | an `io.netty.handler.ssl.SslContext` object or a map of SSL context options (see `aleph.netty/ssl-client-context` for more details), only required if a custom context is desired. When passing an `io.netty.handler.ssl.SslContext` object, it must have an ALPN config matching the `http-versions` option (see `aleph.netty/ssl-client-context` and `aleph.netty/application-protocol-config`). If only HTTP/1.1 is desired, ALPN config is optional.
   | `ssl-endpoint-id-alg`     | the name of the algorithm to use for SSL endpoint identification (see https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#endpoint-identification-algorithms), defaults to \"HTTPS\". Only used for HTTPS connections. Pass `nil` to disable endpoint identification.
   | `local-address`           | an optional `java.net.SocketAddress` describing which local interface should be used
   | `bootstrap-transform`     | a function that takes an `io.netty.bootstrap.Bootstrap` object and modifies it.
   | `pipeline-transform`      | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `insecure?`               | if `true`, ignores the certificate for any `https://` domains
   | `response-buffer-size`    | the amount of the response, in bytes, that is buffered before the request returns, defaults to `65536`.  This does *not* represent the maximum size response that the client can handle (which is unbounded), and is only a means of maximizing performance.
   | `keep-alive?`             | if `true`, attempts to reuse connections for multiple requests, defaults to `true`.
   | `connect-timeout`         | timeout for a connection to be established, in milliseconds. Default determined by Netty, see `aleph.netty/default-connect-timeout`.
   | `idle-timeout`            | when set, forces keep-alive connections to be closed after an idle time, in milliseconds.
   | `transport`               | the transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio`).
   | `raw-stream?`             | if `true`, bodies of responses will not be buffered at all, and represented as Manifold streams of `io.netty.buffer.ByteBuf` objects rather than as an `InputStream`.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `max-initial-line-length` | the maximum length of the initial line (e.g. HTTP/1.0 200 OK), defaults to `65536`
   | `max-header-size`         | the maximum characters that can be in a single header entry of a response, defaults to `65536`
   | `max-chunk-size`          | the maximum characters that can be in a single chunk of a streamed response, defaults to `65536`
   | `name-resolver`           | specify the mechanism to resolve the address of the unresolved named address. When not set or equals to `:default`, JDK's built-in domain name lookup mechanism is used (blocking). Set to`:noop` not to resolve addresses or pass an instance of `io.netty.resolver.AddressResolverGroup` you need. Note, that if the appropriate connection-pool is created with dns-options shared DNS resolver would be used
   | `proxy-options`           | a map to specify proxy settings. HTTP, SOCKS4 and SOCKS5 proxies are supported. Note, that when using proxy `connections-per-host` configuration is still applied to the target host disregarding tunneling settings. If you need to limit number of connections to the proxy itself use `total-connections` setting.
   | `response-executor`       | optional `java.util.concurrent.Executor` that will execute response callbacks
   | `log-activity`            | when set, logs all events on each channel (connection) with a log level given. Accepts one of `:trace`, `:debug`, `:info`, `:warn`, `:error` or an instance of `io.netty.handler.logging.LogLevel`. Note, that this setting *does not* enforce any changes to the logging configuration (default configuration is `INFO`, so you won't see any `DEBUG` or `TRACE` level messages, unless configured explicitly)
   | `http-versions`           | an optional vector of allowable HTTP versions to negotiate via ALPN, in preference order. Defaults to `[:http1]`.

   HTTP/2-specific options
   | Param key                 | Description |
   | ---                       | --- |
   | `force-h2c?`              | an optional boolean indicating you wish to force the use of insecure HTTP/2 cleartext (h2c) for `http://` URLs. Not recommended, and unsupported by most servers in the wild. Only do this with infrastructure you control. Defaults to `false`.
   | `conn-go-away-handler`    | A connection-level cleanup handler for when a GOAWAY is received. Indicates the server has, or soon will, close the TCP connection. Contains the last-processed stream ID.|
   | `stream-go-away-handler`  | A stream-level cleanup handler called for streams above the last-stream-id in a GOAWAY frame. Indicates the stream will not be processed. Called with the context and the Http2GoAwayFrame. |
   | `reset-stream-handler`    | A stream-level cleanup handler called for streams that have been sent RST_STREAM. Called with the context and the Http2ResetFrame. |

   Supported `proxy-options` are

   Param key              | Description
   | ---                  | ---
   | `host`               | host of the proxy server
   | `port`               | an optional port to establish connection (defaults to 80 for http and 1080 for socks proxies)
   | `protocol`           | one of `:http`, `:socks4` or `:socks5` (defaults to `:http`)
   | `user`               | an optional auth username
   | `password`           | an optional auth password
   | `http-headers`       | (HTTP proxy only) an optional map to set additional HTTP headers when establishing connection to the proxy server
   | `tunnel?`            | (HTTP proxy only) if `true`, sends HTTP CONNECT to the proxy and waits for the 'HTTP/1.1 200 OK' response before sending any subsequent requests. Defaults to `false`. When using authorization or specifying additional headers uses tunneling disregarding this setting
   | `connection-timeout` | timeout in milliseconds for the tunnel become established, defaults to 60 seconds, setting is ignored when tunneling is not used."
  [{:keys [connections-per-host
           total-connections
           target-utilization
           connection-options
           dns-options
           stats-callback
           control-period
           middleware
           max-queue-size
           pool-builder-fn
           pool-controller-builder-fn]
    :or   {connections-per-host 8
           total-connections    1024
           target-utilization   0.9
           control-period       60000
           middleware           middleware/wrap-request
           max-queue-size       65536}}]
  (let [{:keys [keep-alive?
                idle-timeout
                http-versions
                force-h2c?]
         :or {idle-timeout 0}} connection-options]
    (when (and (false? keep-alive?) (pos? idle-timeout))
      (throw
       (IllegalArgumentException.
        ":idle-timeout option is not allowed when :keep-alive? is explicitly disabled")))
    (when (and force-h2c? (not-any? #{:http2} http-versions))
      (throw (IllegalArgumentException. "force-h2c? may only be true when HTTP/2 is enabled."))))

  (let [{:keys [log-activity
                ssl-context
                http-versions]
         :or   {http-versions [:http1]}} connection-options
        dns-options' (if-not (and (some? dns-options)
                                  (not (or (contains? dns-options :transport)
                                           (contains? dns-options :epoll?))))
                       dns-options
                       (let [{:keys [epoll? transport]} connection-options
                             transport (netty/determine-transport transport epoll?)]
                         (assoc dns-options :transport transport)))
        conn-options' (cond-> connection-options
                              (some? dns-options')
                              (assoc :name-resolver (netty/dns-resolver-group dns-options'))

                              (some? log-activity)
                              (assoc :log-activity (netty/activity-logger "aleph-client" log-activity))

                              (some? ssl-context)
                              (update :ssl-context
                                      #(-> %
                                           (common/ensure-consistent-alpn-config http-versions)
                                           (netty/coerce-ssl-client-context))))
        p (promise)
        create-pool-fn (or pool-builder-fn
                           flow/instrumented-pool)
        create-pool-ctrl-fn (or pool-controller-builder-fn
                                #(Pools/utilizationController target-utilization connections-per-host total-connections))
        pool (create-pool-fn
               {:generate       (fn [host]
                                  (let [c (promise)
                                        conn (create-connection
                                               host
                                               conn-options'
                                               middleware
                                               #(flow/dispose @p host [@c]))]
                                    (deliver c conn)
                                    [conn]))
                :destroy        (fn [_ c]
                                  (d/chain' c
                                            first
                                            client/close-connection))
                :control-period control-period
                :max-queue-size max-queue-size
                :controller     (create-pool-ctrl-fn)
                :stats-callback stats-callback})]
    @(deliver p pool)))

(def ^:no-doc default-connection-pool
  (connection-pool
    {:stats-callback
     (fn [s]
       (doseq [c @connection-stats-callbacks]
         (c s)))}))

(defn websocket-client
  "Given a url, returns a deferred which yields a duplex stream that can be used to
   communicate with a server over the WebSocket protocol.

   Param key               | Description
   | ---                   | ---
   | `raw-stream?`         | if `true`, the connection will emit raw `io.netty.buffer.ByteBuf` objects rather than strings or byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `insecure?`           | if `true`, the certificates for `wss://` will be ignored.
   | `ssl-context`         | an `io.netty.handler.ssl.SslContext` object, only required if a custom context is required
   | `ssl-endpoint-id-alg` | the name of the algorithm to use for SSL endpoint identification (see https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#endpoint-identification-algorithms), defaults to \"HTTPS\". Only used for WSS connections. Pass `nil` to disable endpoint identification.
   | `extensions?`         | if `true`, the websocket extensions will be supported.
   | `sub-protocols`       | a string with a comma seperated list of supported sub-protocols.
   | `headers`             | the headers that should be included in the handshake
   | `compression?`        | when set to `true`, enables client to use permessage-deflate compression extension, defaults to `false`.
   | `pipeline-transform`  | an optional function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `max-frame-payload`   | maximum allowable frame payload length, in bytes, defaults to `65536`.
   | `max-frame-size`      | maximum aggregate message size, in bytes, defaults to `1048576`.
   | `connect-timeout`     | timeout for a connection to be established, in milliseconds. Default determined by Netty, see `aleph.netty/default-connect-timeout`.
   | `bootstrap-transform` | an optional function that takes an `io.netty.bootstrap.Bootstrap` object and modifies it.
   | `transport`               | the transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio`).
   | `heartbeats`          | optional configuration to send Ping frames to the server periodically (if the connection is idle), configuration keys are `:send-after-idle` (in milliseconds), `:payload` (optional, empty frame by default) and `:timeout` (optional, to close the connection if Pong is not received after specified timeout)."
  ([url]
   (websocket-client url nil))
  ([url options]
   (ws.client/websocket-connection url options)))

(defn websocket-connection
  "Given an HTTP request that can be upgraded to a WebSocket connection, returns a
   deferred which yields a duplex stream that can be used to communicate with the
   client over the WebSocket protocol.

   Param key              | Description
   | ---                  | ---
   | `raw-stream?`        | if `true`, the connection will emit raw `io.netty.buffer.ByteBuf` objects rather than strings or byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `headers`            | the headers that should be included in the handshake
   | `compression?`       | when set to `true`, enables permessage-deflate compression extention support for the connection, defaults to `false`.
   | `pipeline-transform` | an optional function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `max-frame-payload`  | maximum allowable frame payload length, in bytes, defaults to `65536`.
   | `max-frame-size`     | maximum aggregate message size, in bytes, defaults to `1048576`.
   | `allow-extensions?`  | if true, allows extensions to the WebSocket protocol, defaults to `false`.
   | `heartbeats`         | optional configuration to send Ping frames to the client periodically (if the connection is idle), configuration keys are `:send-after-idle` (in milliseconds), `:payload` (optional, empty uses empty frame by default) and `:timeout` (optional, to close the connection if Pong is not received after specified timeout)."
  ([req]
   (websocket-connection req nil))
  ([req options]
   (ws.server/initialize-websocket-handler req options)))

(defn websocket-ping
  "Takes a websocket endpoint (either client or server) and returns a deferred that will
   yield true whenever the PONG comes back, or false if the connection is closed. Subsequent
   PINGs are supressed to avoid ambiguity in a way that the next PONG trigger all pending PINGs."
  ([conn]
   (ws.common/websocket-ping conn (d/deferred) nil))
  ([conn d']
   (ws.common/websocket-ping conn d' nil))
  ([conn d' data]
   (ws.common/websocket-ping conn d' data)))

(defn websocket-close!
  "Closes given websocket endpoint (either client or server) sending Close frame with provided
   status code and reason text. Returns a deferred that will yield `true` whenever the closing
   handshake was initiated with given params or `false` if the connection was already closed.
   Note, that for the server closes the connection right after Close frame was flushed but the
   client waits for the connection to be closed by the server (no longer than close handshake
   timeout, see websocket connection configuration for more details)."
  ([conn]
   (websocket-close! conn ws.common/close-empty-status-code "" nil))
  ([conn status-code]
   (websocket-close! conn status-code "" nil))
  ([conn status-code reason-text]
   (websocket-close! conn status-code reason-text nil))
  ([conn status-code reason-text deferred]
   (let [d' (or deferred (d/deferred))]
     (ws.common/websocket-close! conn status-code reason-text d'))))

(let [maybe-timeout! (fn [d timeout] (when d (d/timeout! d timeout)))]
  (defn request
    "Takes an HTTP request, as defined by the Ring protocol, with the extensions defined
     by [clj-http](https://github.com/dakrone/clj-http), and returns a deferred representing
     the HTTP response.  Also allows for a custom `pool` or `middleware` to be defined.

     Putting the returned deferred into an error state will cancel the underlying request if it is
     still in flight.

     Param key            | Description
     -------------------- | -----------------------------------------------------------------------------------------------------------------------------------------------------------------
     `connection-timeout` | timeout in milliseconds for the connection to become established, defaults to `aleph.netty/default-connect-timeout`. Note that this timeout will be ineffective if the pool's `connect-timeout` is lower.
     `follow-redirects?`  | whether to follow redirects, defaults to `true`; see `aleph.http.client-middleware/handle-redirects`
     `middleware`         | custom client middleware for the request
     `pool-timeout`       | timeout in milliseconds for the pool to generate a connection
     `pool`               | a custom connection pool
     `read-timeout`       | timeout in milliseconds for the response to be completed
     `request-timeout`    | timeout in milliseconds for the arrival of a response over the established connection
     `response-executor`  | optional `java.util.concurrent.Executor` that will handle the requests (defaults to a `flow/utilization-executor` of 256 `max-threads` and a `queue-length` of 0)"
    [{:keys [pool
             middleware
             pool-timeout
             response-executor
             connection-timeout
             request-timeout
             read-timeout]
      :or   {pool               default-connection-pool
             response-executor  default-response-executor
             middleware         identity
             connection-timeout aleph.netty/default-connect-timeout}
      :as   req}]
    (let [dispose-conn! (atom (fn []))
          result (d/deferred response-executor)
          response (executor/with-executor response-executor
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
                                  ;; NOTE: All error handlers below delegate disposal of the
                                  ;; connection to the error handler on `result` which uses this
                                  ;; function.
                                  (reset! dispose-conn! (fn [] (flow/dispose pool k conn)))

                                  (if (realized? result)
                                    ;; to account for race condition between setting `dispose-conn!`
                                    ;; and putting `result` into error state for cancellation
                                    (@dispose-conn!)
                                    ;; get the wrapper for the connection, which may or may not be realized yet
                                    (-> (first conn)
                                        (maybe-timeout! connection-timeout)

                                        ;; connection establishment failed
                                        (d/catch'
                                         (fn [e]
                                           (cond
                                             ;; Handled separately because it inherits from
                                             ;; TimeoutException but we don't want to wrap it in
                                             ;; ConnectionTimeoutException.
                                             (instance? ProxyConnectionTimeoutException e)
                                             (do
                                               (log/trace "Timed out waiting for proxy connection to be established")
                                               (d/error-deferred e))

                                             (or (instance? TimeoutException e)
                                                 ;; Unintuitively, this type doesn't inherit from TimeoutException
                                                 (instance? ConnectTimeoutException e))
                                             (do
                                               (log/trace "Timed out waiting for connection to be established")
                                               (d/error-deferred (ConnectionTimeoutException. ^Throwable e)))

                                             :else
                                             (do
                                               (log/trace "Connection establishment failed")
                                               (d/error-deferred e)))))

                                        ;; actually make the request now
                                        (d/chain'
                                         (fn [conn']
                                           (when-not (nil? conn')
                                             (let [end (System/currentTimeMillis)]
                                               (-> (conn' req)
                                                   (maybe-timeout! request-timeout)

                                                   ;; request timeout triggered
                                                   (d/catch' TimeoutException
                                                             (fn [^Throwable e]
                                                               (d/error-deferred (RequestTimeoutException. e))))

                                                   ;; clean up the connection
                                                   (d/chain'
                                                    (fn cleanup-conn [rsp]

                                                      ;; either destroy/dispose of the conn, or release it back for reuse
                                                      (-> (:aleph/destroy-conn? rsp)
                                                          (maybe-timeout! read-timeout)

                                                          ;; read timeout triggered
                                                          (d/catch' TimeoutException
                                                                    (fn [^Throwable e]
                                                                      (log/trace "Request timed out.")
                                                                      (d/error-deferred (ReadTimeoutException. e))))

                                                          (d/chain'
                                                           (fn [early?]
                                                             (if (or early?
                                                                     (not (:aleph/keep-alive? rsp))
                                                                     (<= 400 (:status rsp)))
                                                               (do
                                                                 (log/trace "Connection finished. Disposing...")
                                                                 (flow/dispose pool k conn))
                                                               (flow/release pool k conn)))))
                                                      (-> rsp
                                                          (dissoc :aleph/destroy-conn?)
                                                          (assoc :connection-time (- end start)))))))))

                                         (fn handle-response [rsp]
                                           (->> rsp
                                                (middleware/handle-cookies req)
                                                (middleware/handle-redirects request req))))))))))))
                      req))]
      (d/connect response result)
      (d/catch' result
                (fn [e]
                  (log/trace e "Request failed. Disposing of connection...")
                  (@dispose-conn!)
                  (d/error-deferred e)))
      result)))

(defn cancel-request!
  "Accepts a response deferred as returned by `request` and closes the underlying TCP connection. If
  the request had already completed by the time this function is invoked, it has no effect (as per
  Manifold deferred semantics). If cancellation succeeded, the deferred will be put into error state
  with an `aleph.utils.RequestCancellationException` instance.

  Note that the request may already have been (partially) processed by the server at the point of
  cancellation."
  [r]
  (d/error! r (RequestCancellationException. "Request cancelled")))

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
      :or   {pool       default-connection-pool
             middleware identity}
      :as   options}]])

(defmacro ^:private def-http-method [method]
  `(do
     (def ~method (partial req ~(keyword method)))
     (alter-meta! (resolve '~method) assoc
                  :doc ~(str "Makes a " (str/upper-case (str method)) " request, returns a deferred representing
   the response.

   Param key              | Description
   | ---                  | ---
   | `headers`            | the HTTP headers for the request
   | `body`               | an optional body, which should be coerce-able to a byte representation via [byte-streams](https://github.com/clj-commons/byte-streams)
   | `middleware`         | any additional middleware that should be used for handling requests and responses
   | `multipart`          | a vector of bodies
   | `follow-redirects?`  | whether to follow redirects, defaults to `true`; see `aleph.http.client-middleware/handle-redirects`
   | `pool`               | a custom connection pool
   | `pool-timeout`       | timeout in milliseconds for the pool to generate a connection
     `connection-timeout` | timeout in milliseconds for the connection to become established, defaults to `aleph.netty/default-connect-timeout`. Note that this timeout will be ineffective if the pool's `connect-timeout` is lower.
   | `request-timeout`    | timeout in milliseconds for the arrival of a response over the established connection
   | `read-timeout`       | timeout in milliseconds for the response to be completed
   | `response-executor`  | optional `java.util.concurrent.Executor` that will handle the requests (defaults to a `flow/utilization-executor` of 256 `max-threads` and a `queue-length` of 0)")
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
  "Given a header map from an HTTP request or response, returns a collection of
   values associated with the key, rather than a comma-delimited string."
  [header-m ^String k]
  (common/get-header-values header-m k))

(defn wrap-ring-async-handler
  "Converts given asynchronous Ring handler to Aleph-compliant handler.

   More information about asynchronous Ring handlers and middleware:
   https://www.booleanknot.com/blog/2016/07/15/asynchronous-ring.html"
  [handler]
  (fn [request]
    (let [response (d/deferred)]
      (handler request #(d/success! response %) #(d/error! response %))
      response)))

(defn file
  "Specifies a file or a region of the file to be sent over the network.
   Accepts string path to the file, instance of `java.io.File` or instance of
   `java.nio.file.Path`."
  ([path]
   (file/http-file path nil nil nil))
  ([path offset length]
   (file/http-file path offset length nil))
  ([path offset length chunk-size]
   (file/http-file path offset length chunk-size)))




(comment
  (do
    (require '[aleph.http.client]
             '[aleph.http.multipart]
             '[clj-commons.byte-streams :as bs])
    (import '(io.netty.channel DefaultFileRegion)
            '(io.netty.handler.stream ChunkedFile ChunkedNioFile)
            '(java.net InetSocketAddress)
            '(java.nio.channels FileChannel)
            '(java.nio.file Files OpenOption Path Paths)
            '(java.nio.file.attribute FileAttribute)))

  (def pool (connection-pool
              {:connection-options
               {:http-versions [:http2]}}))

  ;; basic test
  (def result @(get "https://postman-echo.com?hand=wave" {:pool pool}))

  ;; aleph.localhost
  (-> @(get "https://aleph.localhost:11256/" {:pool pool})
      :body
      bs/to-string)


  ;; basic file post test
  (let [body-string "Body test"
        fpath (Files/createTempFile "test" ".txt" (into-array FileAttribute []))
        ffile (.toFile fpath)
        _ (spit ffile body-string)
        fchan (FileChannel/open fpath (into-array OpenOption []))
        aleph-1k (repeat 1000 \ℵ)
        aleph-20k (repeat 20000 \ℵ)
        aleph-1k-string (apply str aleph-1k)

        body
        body-string
        #_fpath
        #_ffile
        #_(RandomAccessFile. ffile "r")
        #_fchan
        #_(ChunkedFile. ffile)
        #_(ChunkedNioFile. fchan)
        #_(file/http-file ffile 1 6 4)
        #_(DefaultFileRegion. fchan 0 (.size fchan))
        #_(seq body-string)
        #_[:foo :bar :moop]
        #_aleph-20k
        #_[aleph-1k-string aleph-1k-string]]


    (def result
      @(post "https://postman-echo.com/post"
             {:headers        {"content-type" "text/plain"}
              :body           body
              ;;:raw-stream?    true
              })))

  ;; h2c test
  (let [pool (connection-pool
               {:connection-options {:force-h2c? true}})]
    (def result @(get "http://example.com"
                      {:pool              pool
                       :follow-redirects? false})))

  )
