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
     ChannelPipeline]))

(p/def-derived-map TcpConnection [^Channel ch]
  :server-name (some-> ch ^InetSocketAddress (.localAddress) .getHostName)
  :server-port (some-> ch ^InetSocketAddress (.localAddress) .getPort)
  :remote-addr (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress))

(alter-meta! #'->TcpConnection assoc :private true)

(defn- ^ChannelHandler server-channel-handler
  [handler options]
  (let [in (s/stream)]
    (netty/channel-handler

      :exception-caught
      ([_ ctx ex]
         (when-not (instance? IOException ex)
           (log/warn ex "error in TCP server")))

      :channel-inactive
      ([_ ctx]
         (s/close! in))

      :channel-active
      ([_ ctx]
         (let [ch (.channel ctx)]
           (handler
             (s/splice
               (netty/sink ch true netty/to-byte-buf)
               in)
             (->TcpConnection ch))))

      :channel-read
      ([_ ctx msg]
         (netty/put! (.channel ctx) in msg)))))

(defn start-server
  "Takes a two-arg handler function which for each connection will be called with a duplex
   stream and a map containing information about the client."
  [handler
   {:keys [port ssl-context bootstrap-transform]
    :or {bootstrap-transform identity}
    :as options}]
  (netty/start-server
    (fn [^ChannelPipeline pipeline]
      (.addLast pipeline "handler" (server-channel-handler handler options)))
    ssl-context
    bootstrap-transform
    nil
    port))

(defn- ^ChannelHandler client-channel-handler
  [options]
  (let [d (d/deferred)
        in (s/stream)]
    [d

     (netty/channel-handler

       :exception-handler
       ([_ ctx ex]
          (when-not (d/error! d ex)
            (log/warn ex "error in TCP client")))

       :channel-inactive
       ([_ ctx]
          (s/close! in))

       :channel-active
       ([_ ctx]
          (let [ch (.channel ctx)]
            (d/success! d
              (s/splice
                (netty/sink ch true netty/to-byte-buf)
                in))))

       :channel-read
       ([_ ctx msg]
          (netty/put! (.channel ctx) in msg)))]))

(defn client
  "Given a host and port, returns a deferred which yields a duplex stream that can be used
   to communicate with the server."
  [{:keys [host port ssl? insecure? bootstrap-transform]
    :or {bootstrap-transform identity}
    :as options}]
  (let [[s handler] (client-channel-handler options)]
    (netty/create-client
      (fn [^ChannelPipeline pipeline]
        (.addLast pipeline "handler" ^ChannelHandler handler))
      (when ssl?
        (if insecure?
          (netty/insecure-ssl-client-context)
          (netty/ssl-client-context)))
      bootstrap-transform
      host
      port)
    s))
