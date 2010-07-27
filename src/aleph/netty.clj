;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.netty
  (:use
    [clojure.contrib.def :only (defvar- defmacro-)]
    [aleph.core channel pipeline])
  (:import
    [org.jboss.netty.channel
     Channel
     ChannelHandler
     ChannelUpstreamHandler
     ChannelDownstreamHandler
     ChannelHandlerContext
     MessageEvent
     ChannelPipelineFactory
     Channels
     ChannelPipeline]
    [org.jboss.netty.buffer
     ChannelBuffers
     ChannelBuffer
     ChannelBufferInputStream]
    [org.jboss.netty.channel.socket.nio
     NioServerSocketChannelFactory
     NioClientSocketChannelFactory]
    [org.jboss.netty.bootstrap
     ServerBootstrap
     ClientBootstrap]
    [java.util.concurrent
     Executors]
    [java.net
     InetSocketAddress]
    [java.io
     InputStream]))

;;;

(defn event-message
  "Returns contents of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getMessage ^MessageEvent evt)))

(defn event-origin
  "Returns origin of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getRemoteAddress ^MessageEvent evt)))

;;;

(defn upstream-stage
  "Creates a pipeline stage for upstream events."
  [handler]
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      (if-let [upstream-evt (handler evt)]
	(.sendUpstream ctx upstream-evt)))))

(defn downstream-stage
  "Creates a pipeline stage for downstream events."
  [handler]
  (reify ChannelDownstreamHandler
    (handleDownstream [_ ctx evt]
      (if-let [downstream-evt (handler evt)]
	(.sendDownstream ctx downstream-evt)))))

(defn message-stage
  "Creates a final upstream stage that only captures MessageEvents."
  [handler]
  (upstream-stage
    (fn [evt]
      (when-let [msg (event-message evt)]
	(handler (.getChannel ^MessageEvent evt) msg)))))

(defn create-netty-pipeline
  "Creates a pipeline.  Each stage must have a name.

   Example:
   (create-netty-pipeline
     :stage-a a
     :stage-b b)"
  [& stages]
  (let [stages (partition 2 stages)
	pipeline (Channels/pipeline)]
    (doseq [[id stage] stages]
      (.addLast pipeline (name id) stage))
    pipeline))

;;;

(defn input-stream->channel-buffer
  [^InputStream stream]
  (let [ary (make-array Byte/TYPE (.available stream))]
    (.read stream ary)
    (ChannelBuffers/wrappedBuffer ary)))

(defn channel-buffer->input-stream
  [^ChannelBuffer buf]
  (ChannelBufferInputStream. buf))

;;;

(def thread-pool (Executors/newCachedThreadPool))

(defn start-server
  [pipeline-fn port]
  (let [server (ServerBootstrap.
		 (NioServerSocketChannelFactory.
		   thread-pool
		   thread-pool))]
	(.setPipelineFactory server
	  (reify ChannelPipelineFactory
	    (getPipeline [_] (pipeline-fn))))
	(.bind server (InetSocketAddress. port))))

(defn create-client
  [pipeline-fn send-fn host port]
  (let [[inner outer] (channel-pair)
	client (ClientBootstrap.
		 (NioClientSocketChannelFactory.
		   thread-pool
		   thread-pool))]
    (.setPipelineFactory client
      (reify ChannelPipelineFactory
	(getPipeline [_] (pipeline-fn outer))))
    (run-pipeline (.connect client (InetSocketAddress. host port))
      wrap-netty-future
      (fn [^Channel netty-channel]
	(receive-in-order outer
	  #(.write netty-channel (send-fn %)))
	inner))))

;;;


