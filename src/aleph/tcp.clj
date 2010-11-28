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
    [lamina.core]
    [gloss core io])
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
     ChannelBuffer
     ChannelBuffers]
    [java.io
     InputStream]))

(defn- add-delimiter-stage [pipeline delimiters strip-delimiters]
  (.addFirst ^ChannelPipeline pipeline "delimiter"
    (DelimiterBasedFrameDecoder.
      1048576
      strip-delimiters
      (into-array (map to-channel-buffer delimiters)))))

(defn create-frame [frame delimiters strip-delimiters?]
  (cond
    (and frame delimiters) (delimited-frame delimiters frame)
    (and frame (not delimiters)) (compile-frame frame)
    (and (not frame) delimiters) (delimited-block delimiters (or strip-delimiters? true))
    :else nil))

(defn basic-server-pipeline
  [handler send-encoder receive-encoder options]
  (let [[inner outer] (channel-pair)
	decoder (create-frame
		  (or (:decoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))
	encoder (create-frame
		  (or (:encoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))
	send-encoder (if-not encoder
		       send-encoder
		       (comp
			 send-encoder
			 (fn [msg]
			  (let [msg (if (instance? ChannelBuffer msg)
				      (seq (.toByteBuffers ^ChannelBuffer msg))
				      msg)]
			    (encode encoder msg)))))
	inner (if-not decoder
		inner
		(splice (decode-channel decoder inner) inner))]
    (create-netty-pipeline
      ;; :upstream-decoder (upstream-stage (fn [x] (println "server request\n" x) x))
      ;; :downstream-decoder (downstream-stage (fn [x] (println "server response\n" x) x))
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
      :downstream-handler (downstream-stage error-stage-handler))))

(defn basic-client-pipeline
  [ch receive-encoder options]
  (let [decoder (create-frame
		  (or (:decoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))
	ch (if decoder
	     (let [src (channel)
		   ch* (decode-channel decoder src)]
	       (receive-all ch*
		 (fn [msg]
		   (if (closed? ch*)
		     (enqueue-and-close ch msg)
		     (enqueue ch msg))))
	       src)
	     ch)]
    (create-netty-pipeline
      ;; :upstream-decoder (upstream-stage (fn [x] (println "server request\n" x) x))
      ;; :downstream-decoder (downstream-stage (fn [x] (println "server response\n" x) x))
      ;; :upstream-decoder (upstream-stage
      ;; 			  (fn [x]
      ;; 			    (when-let [msg (message-event x)]
      ;; 			      (println "client response:" (.toString msg "utf-8")))
      ;; 			    x))
      ;; :downstream-decoder (downstream-stage
      ;; 			    (fn [x]
      ;; 			      (when-let [msg (message-event x)]
      ;; 				(prn "client request:" (.toString msg "utf-8")))
      ;; 			      x))
      :upstream-error (upstream-stage error-stage-handler)
      :receive (message-stage
		 (fn [netty-channel msg]
		   (enqueue ch (receive-encoder msg))
		   nil))
      :downstream-error (downstream-stage error-stage-handler))))

(defn start-tcp-server [handler options]
  (start-server
    (fn []
      (basic-server-pipeline
	handler
	#(ChannelBuffers/wrappedBuffer (into-array ByteBuffer (to-buf-seq %)))
	#(seq (.toByteBuffers ^ChannelBuffer %))
	options))
    options))

(defn tcp-client [options]
  (let [encoder (create-frame
		  (or (:encoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))]
    (create-client
      (fn [ch] (basic-client-pipeline ch #(seq (.toByteBuffers ^ChannelBuffer %)) options))
      (fn [msg]
	(let [msg (if encoder
		    (encode encoder msg)
		    (to-buf-seq msg))]
	  (ChannelBuffers/wrappedBuffer (into-array ByteBuffer msg))))
      options)))
