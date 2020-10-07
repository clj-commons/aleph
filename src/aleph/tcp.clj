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
    [java.nio.channels
     ClosedChannelException]
    [io.netty.channel
     Channel
     ChannelHandler
     ChannelPipeline]
    [io.netty.handler.logging LoggingHandler]))

(p/def-derived-map TcpConnection [^Channel ch]
  :server-name (netty/channel-server-name ch)
  :server-port (netty/channel-server-port ch)
  :remote-addr (netty/channel-remote-address ch)
  :ssl-session (netty/channel-ssl-session ch))

(alter-meta! #'->TcpConnection assoc :private true)

(defn- ^ChannelHandler server-channel-handler
  [handler {:keys [raw-stream? error-logger] :as options}]
  (let [in (atom nil)]
    (netty/channel-inbound-handler

      :exception-caught
      ([_ ctx ex]
        (let [error-logger' (or error-logger
                                (fn [^Throwable ex]
                                  (when-not (instance? IOException ex)
                                    (log/warn ex "error in TCP server"))))]
          (try
            (error-logger ex)
            (catch Throwable e
              (log/warn e "error in error logger")))
          (when-not (instance? ClosedChannelException)
            (netty/close ctx))))

      :channel-inactive
      ([_ ctx]
        (s/close! @in)
        (.fireChannelInactive ctx))

      :channel-active
      ([_ ctx]
        (let [ch (.channel ctx)]
          (handler
            (doto
              (s/splice
                (netty/sink ch true netty/to-byte-buf)
                (reset! in (netty/source ch)))
              (reset-meta! {:aleph/channel ch}))
            (->TcpConnection ch)))
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
   | `ssl-context` | an `io.netty.handler.ssl.SslContext` object. If a self-signed certificate is all that's required, `(aleph.netty/self-signed-ssl-context)` will suffice.
   | `epoll?` | if `true`, uses `epoll` transport when available, defaults to `false`.
   | `kqueue?` | if `true`, uses `KQueue` transport when available, defaults to `false`.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.ServerBootstrap` object, which represents the server, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?` | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users.
   | `num-event-loop-threads` | optional, defaults to double number of available processors.
   | `error-logger` | optional, function to be invoked on each exception propagated through the pipeline up to the `handler`. Supposed to be used only for logging, crash reporting, metrics, etc rather than error recovery."
  [handler
   {:keys [port
           socket-address
           ssl-context
           bootstrap-transform
           pipeline-transform
           epoll?
           kqueue?
           log-activity
           num-event-loop-threads
           error-logger]
    :or {bootstrap-transform identity
         pipeline-transform identity
         epoll? false
         kqueue? false}
    :as options}]
  (netty/start-server
    {:pipeline-builder (fn [^ChannelPipeline pipeline]
                         (.addLast pipeline "handler" (server-channel-handler handler options))
                         (pipeline-transform pipeline))
     :ssl-context ssl-context
     :bootstrap-transform bootstrap-transform
     :on-close nil
     :socket-address (netty/coerce-socket-address {:socket-address socket-address
                                                   :port port})
     :epoll? epoll?
     :kqueue? kqueue?
     :logger (cond
               (instance? LoggingHandler log-activity)
               log-activity

               (some? log-activity)
               (netty/activity-logger "aleph-server" log-activity)

               :else
               nil)
     :num-event-loop-threads num-event-loop-threads}))

(defn- ^ChannelHandler client-channel-handler
  [{:keys [raw-stream?]}]
  (let [d (d/deferred)
        in (atom nil)]
    [d

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
         (when-not (d/error! d ex)
           (log/warn ex "error in TCP client")))

       :channel-inactive
       ([_ ctx]
         (s/close! @in)
         (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
         (let [ch (.channel ctx)]
           (d/success! d
             (doto
               (s/splice
                (netty/sink ch true netty/to-byte-buf)
                 (reset! in (netty/source ch)))
               (reset-meta! {:aleph/channel ch}))))
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
   | `ssl-context` | an explicit `io.netty.handler.ssl.SslHandler` to use. Defers to `ssl?` and `insecure?` configuration if omitted.
   | `ssl?` | if true, the client attempts to establish a secure connection with the server.
   | `epoll?` | if `true`, uses `epoll` transport when available, defaults to `false`.
   | `kqueue?` | if `true`, uses `KQueue` transport when available, defaults to `false`.
   | `insecure?` | if true, the client will ignore the server's certificate.
   | `bootstrap-transform` | a function that takes an `io.netty.bootstrap.Bootstrap` object, which represents the client, and modifies it.
   | `pipeline-transform` | a function that takes an `io.netty.channel.ChannelPipeline` object, which represents a connection, and modifies it.
   | `raw-stream?` | if true, messages from the stream will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users."
  [{:keys [host
           port
           remote-address
           local-address
           ssl-context
           ssl?
           insecure?
           pipeline-transform
           bootstrap-transform
           epoll?
           kqueue?]
    :or {bootstrap-transform identity
         epoll? false
         kqueue? false}
    :as options}]
  (let [[s handler] (client-channel-handler options)

        pipeline-builder
        (fn [^ChannelPipeline pipeline]
          (.addLast pipeline "handler" ^ChannelHandler handler)
          (when pipeline-transform
            (pipeline-transform pipeline)))]
    (-> (netty/create-client
         {:pipeline-builder pipeline-builder
          :ssl-context (if ssl-context
                         ssl-context
                         (when ssl?
                           (if insecure?
                             (netty/insecure-ssl-client-context)
                             (netty/ssl-client-context))))
          :bootstrap-transform bootstrap-transform
          :remote-address (netty/coerce-socket-address
                           {:scoket-address remote-address
                            :host host
                            :port port})
          :local-address local-address
          :epoll? epoll?
          :kqueue? kqueue?})
        (d/catch' #(d/error! s %)))
    s))
