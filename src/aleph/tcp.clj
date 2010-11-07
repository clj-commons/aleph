;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.tcp
  (:use
    [aleph netty formats]
    [lamina.core])
  (:import
    [org.jboss.netty.channel
     Channel
     ChannelPipeline
     DownstreamMessageEvent
     DefaultChannelFuture]
    [org.jboss.netty.handler.codec.frame
     DelimiterBasedFrameDecoder]
    [java.nio
     ByteBuffer]
    [org.jboss.netty.buffer
     ChannelBuffer]
    [java.io
     InputStream]))

(defn- add-delimiter-stage [pipeline delimiters strip-delimiters]
  (.addFirst ^ChannelPipeline pipeline "delimiter"
    (DelimiterBasedFrameDecoder.
      1048576
      strip-delimiters
      (into-array (map to-channel-buffer delimiters)))))

(defn basic-server-pipeline
  [handler send-encoder receive-encoder options]
  (let [[inner outer] (channel-pair)
	pipeline (create-netty-pipeline
		   ;;:upstream-decoder (upstream-stage (fn [x] (println "server request\n" x) x))
		   ;;:downstream-decoder (downstream-stage (fn [x] (println "server response\n" x) x))
		   :upstream-error (upstream-stage error-stage-handler)
		   :channel-open (upstream-stage
				   (channel-open-stage
				     (fn [^Channel netty-channel]
				       (handler inner {:remote-addr (.getRemoteAddress netty-channel)})
				       (receive-in-order outer
					 #(write-to-channel netty-channel (send-encoder %) (closed? outer))))))
		   :channel-close (upstream-stage
				    (channel-close-stage
				      (fn [_]
					(enqueue-and-close inner nil)
					(enqueue-and-close outer nil))))
		   :receive (message-stage
			      (fn [netty-channel msg]
				(enqueue outer (receive-encoder msg))
			        nil))
		   :downstream-handler (downstream-stage error-stage-handler))]
    (when-let [delimiters (:delimiters options)]
      (add-delimiter-stage pipeline delimiters (or (:strip-delimiters? options) true)))
    pipeline))

(defn basic-client-pipeline
  [ch receive-encoder options]
  (let [pipeline (create-netty-pipeline
		   :upstream-error (upstream-stage error-stage-handler)
		   :receive (message-stage
			      (fn [netty-channel msg]
				(enqueue ch (receive-encoder msg))
				nil))
		   :downstream-error (downstream-stage error-stage-handler))]
    (when-let [delimiters (:delimiters options)]
      (add-delimiter-stage
	pipeline
	delimiters
	(if (contains? options :strip-delimiters?)
	  (:strip-delimiters? options)
	  true)))
    pipeline))

(defn start-tcp-server [handler options]
  (start-server
    #(basic-server-pipeline
       handler
       to-channel-buffer
       channel-buffer->byte-buffer
       options)
    options))

(defn tcp-client [options]
  (create-client
    #(basic-client-pipeline % channel-buffer->byte-buffer options)
    to-channel-buffer
    options))
