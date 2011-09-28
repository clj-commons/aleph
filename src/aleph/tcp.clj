;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
  aleph.tcp
  (:use
    [aleph netty formats]
    [lamina core trace]
    [gloss core io])
  (:require
    [clojure.tools.logging :as log])
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
    [org.jboss.netty.handler.logging
     LoggingHandler]
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBuffers]
    [java.io
     InputStream]))

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
	inner (wrap-write-channel
		(if-not decoder
		  inner
		  (splice (decode-channel inner decoder) inner)))]
    (create-netty-pipeline (:name options)
      :channel-open (upstream-stage
		      (channel-open-stage
			(fn [^Channel netty-channel]
			  (let [write-queue (create-write-queue
					      netty-channel
					      #(write-to-channel netty-channel nil true))]
			    (handler inner {:remote-addr (.getRemoteAddress netty-channel)})
			    (run-pipeline nil
			      :error-handler (fn [ex]
					       (trace [(:name options) :errors]
						 {:exception ex, :channel inner}))
			      (fn [_]
				(receive-in-order outer
				  (fn [[returned-result msg]]
				    (enqueue write-queue
				      (let [result (write-to-channel netty-channel (send-encoder msg) false)]
					(siphon-result result returned-result)
					result))
				    nil)))
			      (fn [_]
				(close write-queue)))))))
      :channel-close (upstream-stage
		       (channel-close-stage
			 (fn [_]
			   (close inner)
			   (close outer))))
      :receive (message-stage
		 (fn [netty-channel msg]
		   (enqueue outer (receive-encoder msg))
		   nil)))))

(defn basic-client-pipeline
  [ch receive-encoder options]
  (let [decoder (create-frame
		  (or (:decoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))
	ch (if decoder
	     (let [src (channel)
		   ch* (decode-channel src decoder)]
	       (receive-all ch*
		 (fn [msg]
		   (if (drained? ch*)
		     (enqueue-and-close ch msg)
		     (enqueue ch msg))))
	       src)
	     ch)]
    (create-netty-pipeline (:name options)
      :receive (message-stage
		 (fn [netty-channel msg]
		   (enqueue ch (receive-encoder msg))
		   nil)))))

(defn start-tcp-server
  "Starts a TCP server. The handler must be a function that takes two parameters,
   'channel' and 'connection-info'.  The channel is for bidirectional communication
   with the client, and the connection-info contains information about the client.

   'options' must specify the :port that the server will listen on.  Optional parameters
   include :frame and :delimiters, which can be used to transform the byte stream into
   structured data.

   :delimiters must be a list of strings, characters, or numbers, which represent tokens
   that split the byte stream into frames.  These tokens will be removed when decoding
   the stream, and the first token will be automatically added to whatever bytes are sent
   to the client.

   :frame specifies a Gloss frame (see http://github.com/ztellman/gloss) that is used to
   encode and decode data sent into the channel.  If used in conjunction with :delimiters,
   it is assumed that the specified tokens delimit full frames, or an error will be thrown.
   An :encoder or :decoder frame may also be specified.  This will override the specified
   :frame.

   If a frame is specified, only data structured per the frame will be accepted (i.e. raw
   bytes are no longer an acceptable input)."
  [handler options]
  (let [options (merge
		  {:name (str "tcp-server:" (:port options))}
		  options)]
    (start-server
      (fn []
	(basic-server-pipeline
	  handler
	  bytes->channel-buffer
	  bytes->byte-buffers
	  options))
      options)))

(defn tcp-client
  "Creates a TCP connection to a server.  Returns a result-channel that will emit a channel
   if it succeeds in connecting to the given :host and :port.  This channel can be used to
   communicate with the server.

   Optional parameters include :frame and :delimiters, which work identically to those in
   start-tcp-server."
  [options]
  (let [options (merge
		  {:name (str "tcp-client:" (:host options) ":" (:port options))}
		  options)
	encoder (create-frame
		  (or (:encoder options) (:frame options))
		  (:delimiters options)
		  (:strip-delimiters? options))]
    (create-client
      (fn [ch] (basic-client-pipeline ch bytes->byte-buffers options))
      (fn [msg]
	(let [msg (if encoder
		    (encode encoder msg)
		    (to-buf-seq msg))]
	  (bytes->channel-buffer msg)))
      options)))
