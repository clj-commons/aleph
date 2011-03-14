(ns ^{:author "Jeff Rose"}
  aleph.udp
  (:use
    [aleph netty formats]
    [lamina.core]
    [gloss core io])
  (:import
    [org.jboss.netty.bootstrap ConnectionlessBootstrap]
    [org.jboss.netty.channel.socket.nio NioDatagramChannelFactory]
    [org.jboss.netty.handler.codec.serialization ObjectEncoder ObjectDecoder]
    [org.jboss.netty.channel.socket DatagramChannel]
    [java.net InetSocketAddress]
    [org.jboss.netty.channel Channel ChannelPipelineFactory]
    [org.jboss.netty.handler.codec.string StringDecoder StringEncoder]
    [org.jboss.netty.util CharsetUtil]
    [java.net InetAddress InetSocketAddress]))

(defn create-udp-socket
  "Returns a result-channel that emits a channel if it successfully opens
  a UDP socket.  Send messages by enqueuing maps containing:

  {:host :port :msg}

  and if bound to a port you can listen by receiving equivalent messages on 
  the channel returned.

  Optional parameters include:
    :port <int>     ; to listen on a specific local port and 
    :broadcast true ; to broadcast from this socket
    :buf-size <int> ; to set the receive buffer size
  "
  [pipeline-fn & {:as options}]
  (let [{:keys [port broadcast buf-size netty]} (merge {:port 0 
                                                        :broadcast false
                                                        :bus-size nil}
                                                       options)
        [in-chan out-chan] (channel-pair)
        client (ConnectionlessBootstrap.
                 (NioDatagramChannelFactory. client-thread-pool))
        local-addr (InetSocketAddress. port)
        netty-opts (if broadcast
                     (assoc netty "broadcast" true)
                     netty)
        nett-opts (if buf-size
                    (assoc netty-opts "receiveBufferSize" buf-size)
                    netty-opts)]
    (.setPipelineFactory client (pipeline-fn out-chan))

    (doseq [[k v] netty-opts]
      (.setOption client k v))

    (run-pipeline (.bind client local-addr)
      (fn [netty-chan]
        (on-closed in-chan
          (fn []
            (close-channel netty-chan false)))

        (receive-in-order out-chan
          (fn [{:keys [host port msg]}]
            (write-to-channel netty-chan msg false :host host :port port)))

        in-chan))))

(defn udp-message-stage
  [handler]
  (upstream-stage
    (fn [evt]
      (when-let [msg (message-event evt)]
        (let [src-addr (event-origin evt)
              host (.getHostAddress (.getAddress src-addr))
              port (.getPort src-addr)]
          (handler msg {:host host :port port}))))))

(defn basic-pipeline-factory
  [out-chan encoder decoder]
  (reify ChannelPipelineFactory
    (getPipeline [_]
                 (create-netty-pipeline
                   :encoder encoder
                   :decoder decoder
                   :receive (udp-message-stage
                              (fn [msg addr]
                                  (enqueue out-chan (assoc addr :msg msg))))))))

(defn create-udp-text-socket
  [& args]
  (apply create-udp-socket 
         #(basic-pipeline-factory %
           (StringEncoder. CharsetUtil/ISO_8859_1)
           (StringDecoder. CharsetUtil/ISO_8859_1))
         args))

(defn create-udp-object-socket
  [& args]
  (apply create-udp-socket 
         #(basic-pipeline-factory %
           (ObjectEncoder.)
           (ObjectDecoder.))
         args))




