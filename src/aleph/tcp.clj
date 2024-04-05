(ns aleph.tcp
  (:require
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [potemkin :as p])
  (:import
    [java.io
     IOException]
    [java.net
     InetSocketAddress]
    [io.netty.channel
     Channel
     ChannelHandler
     ChannelPipeline]))

(p/def-derived-map TcpConnection [^Channel ch]
  :server-name (netty/channel-server-name ch)
  :server-port (netty/channel-server-port ch)
  :remote-addr (netty/channel-remote-address ch)
  :ssl-session (netty/channel-ssl-session ch))

(alter-meta! #'->TcpConnection assoc :private true)

(defn- ^ChannelHandler server-channel-handler
  [handler {:keys [raw-stream?] :as options}]
  (let [in (atom nil)]
    (netty/channel-inbound-handler

      :exception-caught
      ([_ ctx ex]
       (cond
         ;; do not need to log an entire stack trace when SSL handshake failed
         (netty/ssl-handshake-error? ex)
         (log/warn "SSL handshake failure:"
                   (.getMessage ^Throwable (.getCause ^Throwable ex)))

         (not (instance? IOException ex))
         (log/warn ex "error in TCP server")))

      :channel-inactive
      ([_ ctx]
       (some-> @in s/close!)
       (.fireChannelInactive ctx))

      :channel-active
      ([_ ctx]
       (-> (.channel ctx)
           netty/maybe-ssl-handshake-future
           (d/on-realized (fn [ch]
                            (handler
                             (doto (s/splice
                                    (netty/sink ch true netty/to-byte-buf)
                                    (reset! in (netty/source ch)))
                               (reset-meta! {:aleph/channel ch}))
                             (->TcpConnection ch)))
                          netty/ignore-ssl-handshake-errors))
       (.fireChannelActive ctx))

      :channel-read
      ([_ ctx msg]
        (netty/put! (.channel ctx) @in
          (if raw-stream?
            msg
            (netty/release-buf->array msg)))))))

(defn start-server
  "Takes a two-arg handler function which for each connection will be called with a duplex
   stream and a map containing information about the client.  Returns a server object that can
   be shutdown via `java.io.Closeable.close()`, and whose port can be discovered via `aleph.netty.port`.

   Param key               | Description
   | ---                   | ---
   | `port`                | the port the server will bind to.  If `0`, the server will bind to a random port.
   | `socket-address`      | a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `ssl-context`         | an `io.netty.handler.ssl.SslContext` object or a map of SSL context options (see `aleph.netty/ssl-server-context` for more details). If given, the server will only accept SSL connections and call the handler once the SSL session has been successfully established. If a self-signed certificate is all that's required, `(aleph.netty/self-signed-ssl-context)` will suffice.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform`  | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?`         | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `shutdown-timeout`    | interval in seconds within which in-flight requests must be processed, defaults to 15 seconds. A value of 0 bypasses waiting entirely.
   | `transport`           | the transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio`)."
  [handler
   {:keys [port socket-address ssl-context bootstrap-transform pipeline-transform epoll?
           shutdown-timeout transport]
    :or {bootstrap-transform identity
         pipeline-transform identity
         epoll? false
         shutdown-timeout netty/default-shutdown-timeout}
    :as options}]
  (let [ssl-context (some-> ssl-context netty/coerce-ssl-server-context)]
    (netty/start-server
     {:pipeline-builder (fn [^ChannelPipeline pipeline]
                          (when ssl-context
                            (.addFirst pipeline
                                       "ssl-handler"
                                       (let [ch (.channel pipeline)]
                                         (netty/ssl-handler ch ssl-context))))
                          (.addLast pipeline
                                    "handler"
                                    (server-channel-handler handler options))
                          (pipeline-transform pipeline))
      :bootstrap-transform bootstrap-transform
      :socket-address (if socket-address
                        socket-address
                        (InetSocketAddress. port))
      :transport (netty/determine-transport transport epoll?)
      :shutdown-timeout shutdown-timeout})))

(defn- client-channel-handler
  "Returns a vector of `[d handler]`, where `d` is a deferred containing the
   channel's manifold stream, and `handler` coordinates moving data from netty
   to the stream."
  [{:keys [raw-stream?]}]
  (let [d (d/deferred)
        in (atom nil)]
    [d

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
         (when-not (d/error! d ex)
           (if (netty/ssl-handshake-error? ex)
             ;; do not need to log an entire stack trace when SSL handshake failed
             (log/warn "SSL handshake failure:"
                       (.getMessage ^Throwable (.getCause ^Throwable ex)))
             (log/warn ex "error in TCP client"))))

       :channel-inactive
       ([_ ctx]
         (some-> @in s/close!)
         (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
        (-> (.channel ctx)
            netty/maybe-ssl-handshake-future
            (d/on-realized (fn [ch]
                             (d/success! d (doto (s/splice
                                                  (netty/sink ch true netty/to-byte-buf)
                                                  (reset! in (netty/source ch)))
                                             (reset-meta! {:aleph/channel ch}))))
                           netty/ignore-ssl-handshake-errors))
        (.fireChannelActive ctx))

       :channel-read
       ([_ ctx msg]
         (netty/put! (.channel ctx) @in
           (if raw-stream?
             msg
             (netty/release-buf->array msg))))

       :close
       ([_ ctx promise]
         (.close ctx promise)
         (d/error! d (IllegalStateException. "unable to connect"))))]))

(defn client
  "Given a host and port, returns a deferred which yields a duplex stream that can be used
   to communicate with the server.

   Param key               | Description
   | ---                   | ---
   | `host`                | the hostname of the server.
   | `port`                | the port of the server.
   | `remote-address`      | a `java.net.SocketAddress` specifying the server's address.
   | `local-address`       | a `java.net.SocketAddress` specifying the local network interface to use.
   | `ssl-context`         | an explicit `io.netty.handler.ssl.SslHandler` or a map of SSL context options (see `aleph.netty/ssl-client-context` for more details) to use. Defers to `ssl?` and `insecure?` configuration if omitted.
   | `ssl-endpoint-id-alg` | the name of the algorithm to use for SSL endpoint identification (see https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html#endpoint-identification-algorithms), defaults to \"HTTPS\" which is a reasonable default for non-HTTPS uses, too. Only used by SSL connections. Pass `nil` to disable endpoint identification.
   | `ssl?`                | if true, the client attempts to establish a secure connection with the server.
   | `insecure?`           | if true, the client will ignore the server's certificate.
   | `connect-timeout`     | timeout for a connection to be established, in milliseconds. Default determined by Netty, see `aleph.netty/default-connect-timeout`.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.Bootstrap` object, which represents the client, and modifies it.
   | `pipeline-transform`  | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?`         | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `transport`           | the transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio`)."
  [{:keys [host
           port
           remote-address
           local-address
           ssl-context
           ssl-endpoint-id-alg
           ssl?
           insecure?
           connect-timeout
           pipeline-transform
           bootstrap-transform
           epoll?
           transport]
    :or {ssl-endpoint-id-alg netty/default-ssl-endpoint-id-alg
         connect-timeout netty/default-connect-timeout
         bootstrap-transform identity
         epoll? false}
    :as options}]
  (let [[s ^ChannelHandler handler] (client-channel-handler options)
        remote-address (or remote-address (InetSocketAddress. ^String host (int port)))
        ssl? (or ssl? (some? ssl-context))
        ssl-context (if ssl-context
                      (netty/coerce-ssl-client-context ssl-context)
                      (when ssl?
                        (if insecure?
                          (netty/insecure-ssl-client-context)
                          (netty/ssl-client-context))))
        pipeline-builder (fn [^ChannelPipeline pipeline]
                           (when ssl?
                             (.addLast pipeline
                                       "ssl-handler"
                                       (netty/ssl-handler (.channel pipeline) ssl-context remote-address ssl-endpoint-id-alg)))
                           (.addLast pipeline "handler" handler)
                           (when pipeline-transform
                             (pipeline-transform pipeline)))]
    (-> (netty/create-client-chan
          {:pipeline-builder    pipeline-builder
           :bootstrap-transform bootstrap-transform
           :remote-address      remote-address
           :local-address       local-address
           :transport           (netty/determine-transport transport epoll?)
           :connect-timeout     connect-timeout})
        (d/catch' #(d/error! s %)))
    s))
