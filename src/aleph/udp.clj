(ns aleph.udp
  (:require
    [potemkin :as p]
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    [java.net
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
  "Returns a deferred which yields a duplex stream that can be used to send UDP packets
   and, if a port is defined, to receive them as well."
  [{:keys [port broadcast?]}]
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
                                 (let [{:keys [host port message]} msg]
                                   (DatagramPacket.
                                     (netty/to-byte-buf message)
                                     (InetSocketAddress. ^String host (int port))))))
                         in (s/map ->UdpPacket in)]
                     (d/success! d
                       (s/splice out in))))

                :channel-read
                ([_ ctx msg]
                   (netty/put! (.channel ctx) in msg)))))]
    (try
      (-> b (.bind (int (or port 0))))
      d
      (catch Throwable e
        (d/error-deferred e)))))
