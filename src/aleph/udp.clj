;;   Copyright (c) Jeff Rose, Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Jeff Rose, Zachary Tellman"}
  aleph.udp
  (:use
    [aleph netty formats]
    [lamina.core]
    [gloss core io])
  (:require
    [clojure.contrib.logging :as log])
  (:import
    [java.util.concurrent Executors]
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
          (handler msg {:host host :port port})))
      nil)))

(defn udp-pipeline-factory
  [ch frame options & intermediate-stages]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (apply create-netty-pipeline (:name options)
	(concat
	  intermediate-stages
	  [:receive (udp-message-stage
		      (fn [msg addr]
			(let [msg (if frame
				    (decode frame (bytes->byte-buffers msg))
				    msg)]
			  (enqueue ch (assoc addr :message msg)))))])))))

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
  (let [options (merge
		  {:port 0
		   :broadcast false
		   :buf-size nil
		   :name (str "udp-socket:" (or (:port options) (gensym "")))}
		  options)
	{:keys [port broadcast buf-size netty stages frame encoder decoder]} options
        [inner outer] (channel-pair)
        client (ConnectionlessBootstrap.
                 (NioDatagramChannelFactory. (Executors/newCachedThreadPool)))
        local-addr (InetSocketAddress. port)
        netty-opts (if broadcast
                     (assoc netty "broadcast" true)
                     netty)
        netty-opts (if buf-size
		     (assoc netty-opts "receiveBufferSize" buf-size)
		     netty-opts)
	inner (wrap-write-channel inner)]
    (.setPipelineFactory client (apply udp-pipeline-factory
				  outer
				  (or decoder frame)
				  options
				  stages))

    (doseq [[k v] netty-opts]
      (.setOption client k v))

    (run-pipeline (.bind client local-addr)
      (fn [^Channel netty-channel]
	(receive-all outer
	  (fn [[returned-result {:keys [host port message] :as msg}]]
	    (when-not (and (nil? msg) (drained? outer))
	      (let [message (if-let [encoder (or encoder frame)]
			      (bytes->channel-buffer (encode encoder message))
			      message)
		    result (write-to-channel netty-channel message false
			     :host host
			     :port port)]
		(siphon-result result returned-result)
		result))))
	(on-drained outer #(.close netty-channel))
        inner))))

(defn udp-object-socket
  [options]
  (udp-socket
    (merge-with concat
      options
      {:stages [:encoder (ObjectEncoder.)
		:decoder (ObjectDecoder.)]})))




