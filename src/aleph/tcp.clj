;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.tcp
  (:use
    [aleph netty]
    [aleph.core channel])
  (:import
    [org.jboss.netty.channel
     Channel
     DownstreamMessageEvent
     DefaultChannelFuture]))

(defn server-pipeline [handler send-encoder receive-encoder options]
  (let [[inner outer] (channel-pair)
	latch (atom false)]
    (create-netty-pipeline
      :upstream-error (upstream-stage error-stage-handler)
      :channel-open (upstream-stage
		      (channel-open-stage
			(fn [^Channel netty-channel]
			  (handler inner {:remote-addr (.getRemoteAddress netty-channel)})
			  (receive-in-order outer
			    #(when-let [msg (send-encoder %)]
			       (.write netty-channel msg))))))
      :channel-close (upstream-stage
		       (channel-close-stage
			 (fn [_] (enqueue-and-close outer nil))))
      :receive (message-stage
		 (fn [netty-channel msg]
		   (enqueue outer (receive-encoder msg))))
      :downstream-handler (downstream-stage error-stage-handler))))

(defn client-pipeline [ch receive-encoder options]
  (create-netty-pipeline
    :upstream-error (upstream-stage error-stage-handler)
    :receive (message-stage
	       (fn [netty-channel msg]
		 (enqueue ch (receive-encoder msg))))
    :downstream-error (downstream-stage error-stage-handler)))

(defn start-tcp-server [handler options]
  (start-server
    #(server-pipeline
       handler
       byte-buffer->channel-buffer
       channel-buffer->byte-buffer
       options)
    options))

(defn tcp-client [options]
  (create-client
    #(client-pipeline % channel-buffer->byte-buffer options)
    byte-buffer->channel-buffer
    options))
