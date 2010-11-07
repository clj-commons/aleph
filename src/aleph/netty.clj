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
    [lamina.core]
    [aleph formats])
  (:require
    [clj-http.client :as client]
    [clojure.contrib.logging :as log])
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
     ChannelPipeline
     ChannelFuture
     ChannelFutureListener]
    [org.jboss.netty.channel.group
     DefaultChannelGroup
     ChannelGroupFuture
     ChannelGroupFutureListener]
    [org.jboss.netty.channel.socket.nio
     NioServerSocketChannelFactory
     NioClientSocketChannelFactory]
    [org.jboss.netty.bootstrap
     ServerBootstrap
     ClientBootstrap]
    [java.util.concurrent
     Executors]
    [java.net
     URI
     InetSocketAddress]
    [java.io
     InputStream]
    [java.nio
     ByteBuffer]))

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

(defmacro do-once [& body]
  `(let [latch# (atom false)]
     (when (compare-and-set! latch# false true)
       ~@body)))

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

(defn channel-open-stage [f]
  (let [latch (atom false)]
    (fn [evt]
      (when-let [ch (channel-event evt)]
	(when (and (.isConnected ch) (compare-and-set! latch false true))
	  (f ch)))
      nil)))

(defn channel-close-stage [f]
  (let [latch (atom false)]
    (fn [evt]
      (when-let [ch (channel-event evt)]
	(when (and (not (.isConnected ch)) (compare-and-set! latch false true))
	  (f ch)))
      nil)))

(defn error-stage-handler [evt]
  (when (instance? ExceptionEvent evt)
    (log/error (.getCause ^ExceptionEvent evt)))
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

(defn wrap-netty-channel-future
  "Creates a pipeline stage that takes a Netty ChannelFuture, and returns
   a Netty Channel."
  [^ChannelFuture netty-future]
  (let [ch (pipeline-channel)]
    (.addListener netty-future
      (reify ChannelFutureListener
	(operationComplete [_ netty-future]
	  (if (.isSuccess netty-future)
	    (enqueue (:success ch) (.getChannel netty-future))
	    (enqueue (:error ch) [nil (.getCause netty-future)]))
	  nil)))
    ch))

(defn wrap-netty-channel-group-future
  "Creates a pipeline stage that takes a Netty ChannelFuture, and returns
   a Netty Channel."
  [^ChannelGroupFuture netty-future]
  (let [ch (pipeline-channel)]
    (.addListener netty-future
      (reify ChannelGroupFutureListener
	(operationComplete [_ netty-future]
	  (if (.isCompleteSuccess netty-future)
	    (enqueue (:success ch) (.getGroup netty-future))
	    (enqueue (:error ch) [nil (Exception. "Channel-group operation was not completely successful")]))
	  nil)))
    ch))

(defn write-to-channel
  [^Channel netty-channel msg close? & {close-callback :on-close write-callback :on-write}]
  (when (and msg (.isOpen netty-channel))
    (run-pipeline (io! (.write netty-channel msg))
      wrap-netty-channel-future
      (fn [_]
	(when write-callback
	  (write-callback))
	(when close?
	  (run-pipeline (.close netty-channel)
	    wrap-netty-channel-future
	    (fn [_]
	      (when close-callback
		(close-callback)
		nil))))))))

;;;

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 100,
   "tcpNoDelay" true,
   "readWriteFair" true,
   "child.tcpNoDelay" true})

(defn create-pipeline-factory [channel-group pipeline-fn & args]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (let [pipeline (apply pipeline-fn args)]
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

(defn start-server
  "Starts a server.  Returns a function that stops the server."
  [pipeline-fn options]
  (let [port (:port options)
	server (ServerBootstrap.
		 (NioServerSocketChannelFactory.
		   (Executors/newCachedThreadPool)
		   (Executors/newCachedThreadPool)))
	channel-group (DefaultChannelGroup.)]
    (doseq [[k v] (merge default-server-options (:netty options))]
      (.setOption server k v))
    (.setPipelineFactory server
      (create-pipeline-factory channel-group pipeline-fn))
    (.add channel-group (.bind server (InetSocketAddress. port)))
    (fn []
      (run-pipeline
	(.close channel-group)
	wrap-netty-channel-group-future
	(fn [_] (.releaseExternalResources server))))))

;;;

(def default-client-options
  {"tcpNoDelay" true,
   "reuseAddress" true,
   "readWriteFair" true,
   "connectTimeoutMillis" 3000})

(defn split-url [options]
  (if (:url options)
    ((client/wrap-url identity) options)
    options))

(defn create-client
  [pipeline-fn send-fn options]
  (let [options (split-url options)
	host (or (:host options) (:server-name options))
	port (or (:port options) (:server-port options))
	[inner outer] (channel-pair)
	channel-group (DefaultChannelGroup.)
	client (ClientBootstrap.
		 (NioClientSocketChannelFactory.
		   (Executors/newSingleThreadExecutor)
		   (Executors/newSingleThreadExecutor)))]
    (doseq [[k v] (merge default-client-options (:netty options))]
      (.setOption client k v))
    (.setPipelineFactory client
      (create-pipeline-factory channel-group pipeline-fn outer))
    (run-pipeline (.connect client (InetSocketAddress. host port))
      wrap-netty-channel-future
      (fn [^Channel netty-channel]
	(run-pipeline (.getCloseFuture netty-channel)
	  wrap-netty-channel-future
	  (fn [_]
	    (enqueue-and-close inner nil)
	    (enqueue-and-close outer nil)))
	(.add channel-group netty-channel)
	(receive-in-order outer
	  (fn [msg]
	    (write-to-channel
	      netty-channel
	      (send-fn msg)
	      (closed? outer)
	      :on-close #(.releaseExternalResources client))))
	inner))))

;;;


