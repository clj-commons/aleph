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
    [org.jboss.netty.buffer ChannelBuffer]
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
	  [:upstream-error (upstream-stage error-stage-handler)
	   :receive (udp-message-stage
		      (fn [msg addr]
			(let [msg (cond
				    frame
				    (decode frame (channel-buffer->byte-buffers msg))

				    (instance? ChannelBuffer msg)
				    (channel-buffer->byte-buffers msg)

				    :else
				    msg)]
			  (enqueue ch (assoc addr :message msg)))))
	   :downstream-error (downstream-stage error-stage-handler)])))))

(defn udp-socket
  "Returns a result-channel that emits a channel if it successfully opens
  a UDP socket.  Send messages by enqueuing maps containing:

  {:host :port :message}

  and if bound to a port you can listen by receiving equivalent messages on 
  the channel returned.

  Optional parameters include:
    :frame          ; a Gloss frame for encoding and decoding UDP packets
    :decoder        ; a Gloss frame for decoding packets - overrides :frame
    :encoder        ; a Gloss frame for encoding packets - overrides :frame
    :port <int>     ; to listen on a specific local port and 
    :broadcast true ; to broadcast from this socket
    :buf-size <int> ; to set the receive buffer size
  "
  [options]
  (let [{:keys [port broadcast buf-size netty stages frame encoder decoder]}
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
		     netty-opts)
	inner (wrap-write-channel inner)]
    (.setPipelineFactory client (apply udp-pipeline-factory outer (or decoder frame) stages))

    (doseq [[k v] netty-opts]
      (.setOption client k v))

    (run-pipeline (.bind client local-addr)
      (fn [netty-channel]
	(let [write-queue (create-write-queue netty-channel
			    #(write-to-channel netty-channel nil true))]
	  (run-pipeline
	    (receive-in-order outer
	      (fn [[returned-result {:keys [host port message]}]]
		(enqueue write-queue
		  (let [message (if-let [encoder (or encoder frame)]
				  (byte-buffers->channel-buffer (encode encoder message))
				  message)
			result (write-to-channel netty-channel message false
				 :host host
				 :port port)]
		    (siphon-result result returned-result)
		    result))
		nil))
	    :error-handler (fn [ex]
			     (log/error "Error in handler, closing connection." ex)
			     (close write-queue))
	    (fn [_]
	      (close write-queue))))
        inner))))

(defn udp-object-socket
  [options]
  (udp-socket
    (merge-with concat
      options
      {:stages [:encoder (ObjectEncoder.)
		:decoder (ObjectDecoder.)]})))




