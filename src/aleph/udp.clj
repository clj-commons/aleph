;;   Copyright (c) Jeff Rose, Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Jeff Rose"}
  aleph.udp
  (:use
    [aleph netty formats]
    [lamina.core]
    [gloss core io])
  (:require
    [clojure.contrib.logging :as log])
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

(defn udp-message-stage
  [handler]
  (upstream-stage
    (fn [evt]
      (when-let [msg (message-event evt)]
        (let [src-addr (event-origin evt)
              host (.getHostAddress (.getAddress src-addr))
              port (.getPort src-addr)]
          (handler msg {:host host :port port}))))))

(defn udp-pipeline-factory
  [ch frame & intermediate-stages]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (apply create-netty-pipeline
	(concat
	  intermediate-stages
	  [:receive (udp-message-stage
		      (fn [msg addr]
			(println "received" msg addr)
			(let [msg (if frame (decode frame msg) msg)]
			  (enqueue ch (assoc addr :message msg)))))])))))

(defn udp-socket
  "Returns a result-channel that emits a channel if it successfully opens
  a UDP socket.  Send messages by enqueuing maps containing:

  {:host :port :message}

  and if bound to a port you can listen by receiving equivalent messages on 
  the channel returned.

  Optional parameters include:
    :port <int>     ; to listen on a specific local port and 
    :broadcast true ; to broadcast from this socket
    :buf-size <int> ; to set the receive buffer size
  "
  [options]
  (let [{:keys [port broadcast buf-size netty stages frame]}
	(merge
	  {:port 0 
	   :broadcast false
	   :buf-size nil}
	  options)
        [inner outer] (channel-pair)
        client (ConnectionlessBootstrap.
                 (NioDatagramChannelFactory. client-thread-pool))
        local-addr (InetSocketAddress. port)
        netty-opts (if broadcast
                     (assoc netty "broadcast" true)
                     netty)
        netty-opts (if buf-size
		     (assoc netty-opts "receiveBufferSize" buf-size)
		     netty-opts)]
    (.setPipelineFactory client (apply udp-pipeline-factory outer frame stages))

    (doseq [[k v] netty-opts]
      (.setOption client k v))

    (run-pipeline (.bind client local-addr)
      (fn [netty-channel]
	(let [write-channel (create-write-channel netty-channel
			      #(write-to-channel netty-channel nil true))]
	  (run-pipeline
	    (receive-in-order outer
	      (fn [{:keys [host port message]}]
		(enqueue write-channel
		  (let [message (if frame
				  (byte-buffers->channel-buffer (encode frame message))
				  message)]
		    (write-to-channel netty-channel message false
		      :host host
		      :port port)))
		nil))
	    :error-handler (fn [ex]
			     (log/error "Error in handler, closing connection." ex)
			     (close write-channel))
	    (fn [_]
	      (close write-channel))))
        inner))))

(defn udp-object-socket
  [options]
  (udp-socket
    (merge-with concat
      options
      {:stages [:encoder (ObjectEncoder.)
		:decoder (ObjectDecoder.)]})))




