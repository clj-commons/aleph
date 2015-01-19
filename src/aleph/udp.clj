(ns aleph.udp
  (:require
    [potemkin :as p]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    [java.net
     SocketAddress
     InetSocketAddress]
    [io.netty.channel
     ChannelOption]
    [io.netty.bootstrap
     Bootstrap]
    [io.netty.channel.nio
     NioEventLoopGroup]
    [io.netty.channel.socket
     DatagramPacket]
    [io.netty.channel.socket.nio
     NioDatagramChannel]))

(p/def-derived-map UdpPacket [^DatagramPacket packet]
  :host (-> packet ^InetSocketAddress (.sender) .getHostName)
  :port (-> packet ^InetSocketAddress (.sender) .getPort)
  :message (.content packet))

(alter-meta! #'->UdpPacket assoc :private true)

(defn socket
  "Returns a deferred which yields a duplex stream that can be used to send and receive UDP datagrams.

   |:---|:---
   | `port` | the port at which UDP packets can be received.  If both this and `:socket-address` are undefined, packets can only be sent.
   | `socket-address` | a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `broadcast?` | if true, all UDP datagrams are broadcast."
  [{:keys [socket-address port broadcast?]}]
  (let [in (s/stream)
        d (d/deferred)
        g (NioEventLoopGroup.)
        b (doto (Bootstrap.)
            (.group g)
            (.channel NioDatagramChannel)
            (.option ChannelOption/SO_BROADCAST (boolean broadcast?))
            (.handler
              (netty/channel-handler
                :exception-handler
                ([_ ctx ex]
                   (when-not (d/error! d ex)
                     (log/warn ex "error in UDP socket")))

                :channel-active
                ([_ ctx]
                   (let [ch (.channel ctx)
                         out (netty/sink ch true
                               (fn [msg]
                                 (let [{:keys [host port message socket-address]} msg]
                                   (DatagramPacket.
                                     (netty/to-byte-buf message)
                                     (or socket-address
                                       (InetSocketAddress. ^String host (int port)))))))
                         in (s/map ->UdpPacket in)]
                     (d/success! d
                       (s/splice out in))))

                :channel-read
                ([_ ctx msg]
                   (netty/put! (.channel ctx) in msg)))))
        socket-address (or socket-address (InetSocketAddress. (or port 0)))]
    (try
      (-> b (.bind ^SocketAddress socket-address))
      d
      (catch Throwable e
        (d/error-deferred e)))))
