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
    [aleph.core channel pipeline]
    [aleph formats])
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
	  (f ch))))))

(defn channel-close-stage [f]
  (let [latch (atom false)]
    (fn [evt]
      (when-let [ch (channel-event evt)]
	(when (and (not (.isConnected ch)) (compare-and-set! latch false true))
	  (f ch))))))

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

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 100,
   "tcpNoDelay" true,
   "readWriteFair" true,
   "child.tcpNoDelay" true})

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

(def default-client-options
  {"tcpNoDelay" true,
   "reuseAddress" true,
   "readWriteFair" true,
   "connectTimeoutMillis" 3000})

(defn create-client
  [pipeline-fn send-fn options]
  (let [host (or (:host options) (:server-name options))
	port (or (:port options) (:server-port options))
	[inner outer] (channel-pair)
	client (ClientBootstrap.
		 (NioClientSocketChannelFactory.
		   (Executors/newCachedThreadPool)
		   (Executors/newCachedThreadPool)))]
    (doseq [[k v] (merge default-client-options (:netty options))]
      (.setOption client k v))
    (.setPipelineFactory client
      (reify ChannelPipelineFactory
	(getPipeline [_] (pipeline-fn outer))))
    (run-pipeline (.connect client (InetSocketAddress. host port))
      wrap-netty-future
      (fn [^Channel netty-channel]
	(receive-in-order outer
	  #(try
	     (when-let [msg (and % (send-fn %))]
	       (.write netty-channel msg))
	     (when (closed? outer)
	       (.close netty-channel))
	     (catch Exception e
	       (.printStackTrace e))))
	inner))))

;;;


