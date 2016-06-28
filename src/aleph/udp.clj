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
     NioDatagramChannel]
    [io.netty.channel.epoll
     EpollDatagramChannel]))

(p/def-derived-map UdpPacket [^DatagramPacket packet content]
  :sender (-> packet ^InetSocketAddress (.sender))
  :message content)

(alter-meta! #'->UdpPacket assoc :private true)

(defn socket
  "Returns a deferred which yields a duplex stream that can be used to send and receive UDP datagrams.

   |:---|:---
   | `port` | the port at which UDP packets can be received.  If both this and `:socket-address` are undefined, packets can only be sent.
   | `socket-address` | a `java.net.SocketAddress` specifying both the port and interface to bind to.
   | `broadcast?` | if true, all UDP datagrams are broadcast.
   | `bootstrap-transform` | a function which takes the Netty `Bootstrap` object, and makes any desired changes before it's bound to a socket.
   | `raw-stream?` | if true, the `:message` within each packet will be `io.netty.buffer.ByteBuf` objects rather than byte-arrays.  This will minimize copying, but means that care must be taken with Netty's buffer reference counting.  Only recommended for advanced users."
  [{:keys [socket-address port broadcast? raw-stream? bootstrap-transform epoll?]
    :or {epoll? false
         broadcast? false
         raw-stream? false
         bootstrap-transform identity}}]
  (let [in (atom nil)
        d (d/deferred)
        epoll? (and epoll? (netty/epoll-available?))
        g (if epoll?
            @netty/epoll-client-group
            @netty/nio-client-group)
        b (doto (Bootstrap.)
            (.group g)
            (.channel (if epoll? EpollDatagramChannel NioDatagramChannel))
            (.option ChannelOption/SO_BROADCAST (boolean broadcast?))
            (.handler
              (netty/channel-handler
                :exception-caught
                ([_ ctx ex]
                  (when-not (d/error! d ex)
                    (log/warn ex "error in UDP socket")))

                :channel-active
                ([_ ctx]
                  (let [ch (.channel ctx)
                        in (reset! in (netty/source ch))
                        out (netty/sink ch true
                              (fn [msg]
                                (let [{:keys [host port message socket-address]} msg]
                                  (DatagramPacket.
                                    (netty/to-byte-buf message)
                                    (or socket-address
                                      (InetSocketAddress. ^String host (int port)))))))
                        in (s/map
                             (fn [^DatagramPacket packet]
                               (->UdpPacket
                                 packet
                                 (if raw-stream?
                                   (.content packet)
                                   (netty/release-buf->array (.content packet)))))
                             in)]

                    (d/success! d
                      (doto
                        (s/splice out in)
                        (reset-meta! {:aleph/channel ch})))))

                :channel-read
                ([_ ctx msg]
                  (netty/put! (.channel ctx) @in msg)))))
        socket-address (or socket-address (InetSocketAddress. (or port 0)))]
    (try
      (bootstrap-transform b)
      (let [cf (.bind b ^SocketAddress socket-address)]
        (d/chain
          (d/zip (netty/wrap-future cf) d)
          (fn [[_ s]]
            (s/on-closed s #(netty/close (.channel cf))))))
      d
      (catch Throwable e
        (d/error-deferred e)))))
