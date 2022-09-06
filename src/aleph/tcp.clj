(ns aleph.tcp
  (:require
    [potemkin :as p]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log])
  (:import
    [java.io
     IOException]
    [java.net
     InetSocketAddress]
    [io.netty.channel
     Channel
     ChannelHandler
     ChannelHandlerContext
     ChannelPipeline]
    [io.netty.handler.ssl
     SslHandler]))

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

   |:---|:-----
   | `port` | the port the server will bind to.  If `0`, the server will bind to a random port.
   | `socket-address` | a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object or a map of SSL context options (see `aleph.netty/ssl-server-context` for more details). If given, the server will only accept SSL connections and call the handler once the SSL session has been successfully established. If a self-signed certificate is all that's required, `(aleph.netty/self-signed-ssl-context)` will suffice.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?` | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users."
  [handler
   {:keys [port socket-address ssl-context bootstrap-transform pipeline-transform epoll?]
    :or {bootstrap-transform identity
         pipeline-transform identity
         epoll? false}
    :as options}]
  (netty/start-server
    (fn [^ChannelPipeline pipeline]
      (.addLast pipeline "handler" (server-channel-handler handler options))
      (pipeline-transform pipeline))
    ssl-context
    bootstrap-transform
    nil
    (if socket-address
      socket-address
      (InetSocketAddress. port))
    epoll?))

(defn- ^ChannelHandler client-channel-handler
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

   |:---|:----
   | `host` | the hostname of the server.
   | `port` | the port of the server.
   | `remote-address` | a `java.net.SocketAddress` specifying the server's address.
   | `local-address` | a `java.net.SocketAddress` specifying the local network interface to use.
   | `ssl-context` | an explicit `io.netty.handler.ssl.SslHandler` or a map of SSL context options (see `aleph.netty/ssl-server-context` for more details) to use. Defers to `ssl?` and `insecure?` configuration if omitted.
   | `ssl?` | if true, the client attempts to establish a secure connection with the server.
   | `insecure?` | if true, the client will ignore the server's certificate.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.Bootstrap` object, which represents the client, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?` | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users."
  [{:keys [host port remote-address local-address ssl-context ssl? insecure? pipeline-transform bootstrap-transform epoll?]
    :or {bootstrap-transform identity
         epoll? false}
    :as options}]
  (let [[s handler] (client-channel-handler options)]
    (->
      (netty/create-client
        (fn [^ChannelPipeline pipeline]
          (.addLast pipeline "handler" ^ChannelHandler handler)
          (when pipeline-transform
            (pipeline-transform pipeline)))
        (if ssl-context
          ssl-context
          (when ssl?
            (if insecure?
              (netty/insecure-ssl-client-context)
              (netty/ssl-client-context))))
        bootstrap-transform
        (or remote-address (InetSocketAddress. ^String host (int port)))
        local-address
        epoll?)
      (d/catch' #(d/error! s %)))
    s))
