;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.netty
  (:use
    [clojure.contrib.def :only (defvar- defmacro-)]
    [lamina.core]
    [lamina.core.pipeline :only (success! error!)]
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
     ChannelGroup
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
     Executors
     Executor]
    [java.net
     URI
     InetSocketAddress
     InetAddress]
    [java.io
     InputStream]
    [java.nio
     ByteBuffer]))

;;;

(def netty-thread-pool (Executors/newCachedThreadPool))

(defn enqueue-task [f]
  (let [result (result-channel)]
    (.submit ^Executor netty-thread-pool
      #(siphon-result
	 (run-pipeline nil
	   (fn [_] (f)))
	 result))
    result))

;;;

(defn channel-origin [netty-channel]
  (let [socket-address (.getRemoteAddress ^Channel netty-channel)
	inet-address (.getAddress ^InetSocketAddress socket-address)]
    (.getHostAddress ^InetAddress inet-address)))

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

(defn create-write-queue [^Channel netty-channel close-callback]
  (let [ch (channel)]
    (run-pipeline (receive-in-order ch identity)
      (fn [_] (close-callback)))
    ch))

(defn wrap-write-channel [ch]
  (proxy-channel
    (fn [msgs]
      (if (= 1 (count msgs))
	(let [result (result-channel)]
	  [result [[result (first msgs)]]])
	(let [results (repeatedly (count msgs) #(result-channel))]
	  [(apply run-pipeline nil (map constantly results))
	   (map vector results msgs)])))
    ch))

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
      (when-let [ch ^Channel (channel-event evt)]
	(when (and (.isConnected ch) (compare-and-set! latch false true))
	  (f ch)))
      nil)))

(defn channel-close-stage [f]
  (let [latch (atom false)]
    (fn [evt]
      (when-let [ch ^Channel (channel-event evt)]
	(when (and (not (.isConnected ch)) (compare-and-set! latch false true))
	  (f ch)))
      nil)))

(defn error-stage-handler [evt]
  (when (instance? ExceptionEvent evt)
    (log/warn "aleph.netty" (.getCause ^ExceptionEvent evt)))
  evt)

(defn create-netty-pipeline
  "Creates a pipeline.  Each stage must have a name.

   Example:
   (create-netty-pipeline
     :stage-a a
     :stage-b b)"
  [& stages]
  (let [netty-pipeline (Channels/pipeline)]
    (doseq [[id stage] (partition 2 stages)]
      (.addLast netty-pipeline (name id) stage))
    netty-pipeline))

;;;

(defn wrap-netty-channel-future
  "Creates a pipeline stage that takes a Netty ChannelFuture, and returns
   a Netty Channel."
  [^ChannelFuture netty-future]
  (let [ch (result-channel)]
    (.addListener netty-future
      (reify ChannelFutureListener
	(operationComplete [_ netty-future]
	  (if (.isSuccess netty-future)
	    (success! ch (.getChannel netty-future))
	    (error! ch (.getCause netty-future)))
	  nil)))
    ch))

(defn wrap-netty-channel-group-future
  "Creates a pipeline stage that takes a Netty ChannelFuture, and returns
   a Netty Channel."
  [^ChannelGroupFuture netty-future]
  (let [ch (result-channel)]
    (.addListener netty-future
      (reify ChannelGroupFutureListener
	(operationComplete [_ netty-future]
	  (if (.isCompleteSuccess netty-future)
	    (success! ch (.getGroup netty-future))
	    (error! ch (Exception. "Channel-group operation was not completely successful")))
	  nil)))
    ch))

(defn close-channel
  ([netty-channel]
     (close-channel netty-channel nil))
  ([^Channel netty-channel close-callback]
     (run-pipeline (.close netty-channel)
       wrap-netty-channel-future
       (fn [_]
	 (when close-callback
	   (close-callback))
	 true))))

(defn write-to-channel
  [^Channel netty-channel msg close?
   & {close-callback :on-close write-callback :on-write host :host port :port}]
  (if msg
    (run-pipeline
      (io!
	(if (and host port)
	  (.write netty-channel msg (InetSocketAddress. host port))
	  (.write netty-channel msg)))
      wrap-netty-channel-future
      (fn [_]
	(when write-callback
	  (write-callback))
	(if close?
	  (close-channel netty-channel close-callback)
	  true)))
    (when close?
      (close-channel netty-channel close-callback))))

;;;

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 100,
   "tcpNoDelay" true,
   "readWriteFair" true,
   "child.tcpNoDelay" true})

(defn create-pipeline-factory [^ChannelGroup channel-group pipeline-fn & args]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (let [pipeline ^ChannelPipeline (apply pipeline-fn args)]
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
  [pipeline-fn send-encoder options]
  (let [options (split-url options)
	host (or (:host options) (:server-name options))
	port (or (:port options) (:server-port options))
	[inner outer] (channel-pair)
	inner (wrap-write-channel inner)
	channel-group (DefaultChannelGroup.)
	client (ClientBootstrap.
		 (NioClientSocketChannelFactory.
		   netty-thread-pool
		   netty-thread-pool))]
    (doseq [[k v] (merge default-client-options (:netty options))]
      (.setOption client k v))
    (.setPipelineFactory client
      (create-pipeline-factory channel-group pipeline-fn outer))
    (run-pipeline (.connect client (InetSocketAddress. ^String host (int port)))
      wrap-netty-channel-future
      (fn [^Channel netty-channel]
	(let [write-queue (create-write-queue
			    netty-channel
			    #(write-to-channel netty-channel nil true))]
	  (run-pipeline (.getCloseFuture netty-channel)
	    wrap-netty-channel-future
	    (fn [_]
	      (close inner)
	      (close outer)))
	  (.add channel-group netty-channel)
	  (run-pipeline
	    (receive-in-order outer
	      (fn [[returned-result msg]]
		(enqueue write-queue
		  (let [result (write-to-channel netty-channel (send-encoder msg) false)]
		    (siphon-result result returned-result)
		    result))))
	    (fn [_]
	      (close write-queue)))
	  inner)))))

;;;


