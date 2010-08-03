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
     ChannelEvent
     ExceptionEvent
     ChannelPipelineFactory
     Channels
     ChannelPipeline]
    [org.jboss.netty.channel.group
     DefaultChannelGroup]
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

(defn message-event
  "Returns contents of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getMessage ^MessageEvent evt)))

(defn channel-event [evt]
  (when (instance? ChannelEvent evt)
    (.getChannel ^ChannelEvent evt)))

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
	(.sendUpstream ctx upstream-evt)
	(.sendUpstream ctx evt)))))

(defn downstream-stage
  "Creates a pipeline stage for downstream events."
  [handler]
  (reify ChannelDownstreamHandler
    (handleDownstream [_ ctx evt]
      (if-let [downstream-evt (handler evt)]
	(.sendDownstream ctx downstream-evt)
	(.sendDownstream ctx evt)))))

(defn message-stage
  "Creates a final upstream stage that only captures MessageEvents."
  [handler]
  (upstream-stage
    (fn [evt]
      (when-let [msg (message-event evt)]
	(handler (.getChannel ^MessageEvent evt) msg)))))

(defn error-stage-handler [evt]
  (when (instance? ExceptionEvent evt)
    (.printStackTrace ^Throwable (.getCause ^ExceptionEvent evt)))
  evt)

(defmacro create-netty-pipeline
  "Creates a pipeline.  Each stage must have a name.

   Example:
   (create-netty-pipeline
     :stage-a a
     :stage-b b)"
  [& stages]
  (let [pipeline-var (gensym "pipeline")]
    `(let [~pipeline-var (Channels/pipeline)]
       ~@(map
	   (fn [[id# stage#]] (list '.addLast pipeline-var (name id#) stage#))
	   (partition 2 stages))
       ~pipeline-var)))

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

(defn start-server
  "Starts a server.  Returns a function that stops the server."
  [pipeline-fn options]
  (let [port (:port options)
	server (ServerBootstrap.
		 (NioServerSocketChannelFactory.
		   (Executors/newCachedThreadPool)
		   (Executors/newCachedThreadPool)))
	channel-group (DefaultChannelGroup.)]
    (.setPipelineFactory server
      (reify ChannelPipelineFactory
	(getPipeline [_]
	  (let [pipeline (pipeline-fn)]
	    (.addFirst pipeline
	      "channel-listener"
	      (upstream-stage
		(fn [evt]
		  (when-let [ch ^Channel (channel-event evt)]
		    (if (.isOpen ch)
		      (.add channel-group ch)
		      (.remove channel-group ch)))
		  nil)))
	      pipeline))))
    (.add channel-group (.bind server (InetSocketAddress. port)))
    (fn []
      (-> channel-group .close .awaitUninterruptibly)
      (.releaseExternalResources server))))

(defn create-client
  [pipeline-fn send-fn options]
  (let [host (or (:host options) (:server-name options))
	port (or (:port options) (:server-port options))
	[inner outer] (channel-pair)
	client (ClientBootstrap.
		 (NioClientSocketChannelFactory.
		   (Executors/newCachedThreadPool)
		   (Executors/newCachedThreadPool)))]
    (.setPipelineFactory client
      (reify ChannelPipelineFactory
	(getPipeline [_] (pipeline-fn outer))))
    (run-pipeline (.connect client (InetSocketAddress. host port))
      wrap-netty-future
      (fn [^Channel netty-channel]
	(receive-in-order outer
	  #(try
	     (.write netty-channel (send-fn %))
	     (catch Exception e
	       (.printStackTrace e))))
	inner))))

;;;


