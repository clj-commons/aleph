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
    [lamina core trace api]
    [aleph formats core]
    [gloss core])
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
    [org.jboss.netty.buffer
     ChannelBuffer]
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
     SocketAddress
     InetSocketAddress
     InetAddress]
    [java.io
     InputStream]
    [java.nio.channels
     ClosedChannelException]
    [java.nio
     ByteBuffer]))

;;;

(defn channel-origin [netty-channel]
  (when-let [socket-address (.getRemoteAddress ^Channel netty-channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn message-event
  "Returns contents of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getMessage ^MessageEvent evt)))

(defn channel-event [evt]
  (when (instance? ChannelEvent evt)
    (.getChannel ^ChannelEvent evt)))

(defn ^InetSocketAddress event-origin
  "Returns origin of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? MessageEvent evt)
    (.getRemoteAddress ^MessageEvent evt)))

(defn exception-event
  "Returns origin of message event, or nil if it's a different type of message."
  [evt]
  (when (instance? ExceptionEvent evt)
    (.getCause ^ExceptionEvent evt)))

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
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      (if-let [msg (message-event evt)]
	(handler (.getChannel ^MessageEvent evt) msg)
	(.sendUpstream ctx evt)))))

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

(defn refuse-connection-stage []
  (reify ChannelUpstreamHandler
    (handleUpstream [_ ctx evt]
      (if-let [ch ^Channel (channel-event evt)]
	(.close ch)
	(.sendUpstream ctx evt)))))

(def pipeline-handlers
  (memoize
    (fn [pipeline-name]
      (let [error-probe (canonical-probe [pipeline-name :errors])
	    error-handler (fn [^ChannelEvent evt]
			    (when-let [ex ^ExceptionEvent (exception-event evt)]
			      (when-not (instance? ClosedChannelException ex)
				(when-not (trace error-probe
					    {:exception ex
					     :address (-> evt .getChannel channel-origin)})
				  (log/error nil ex)))
			      nil))
	    traffic-handler (fn [probe-suffix]
			      (let [traffic-probe (canonical-probe [pipeline-name :traffic probe-suffix])]
				(fn [evt]
				  (when-let [msg (message-event evt)]
				    (trace traffic-probe
				      {:address (-> evt channel-event channel-origin)
				       :bytes (.readableBytes ^ChannelBuffer msg)}))
				  nil)))]
	{:error error-handler
	 :in (traffic-handler :in)
	 :out (traffic-handler :out)}))))

(defn create-netty-pipeline
  "Creates a pipeline.  Each stage must have a name.

   Example:
   (create-netty-pipeline
     :stage-a a
     :stage-b b)"
  [pipeline-name & stages]
  (let [netty-pipeline (Channels/pipeline)
	{:keys [error in out]} (pipeline-handlers pipeline-name)]
    (doseq [[id stage] (partition 2 stages)]
      (.addLast netty-pipeline (name id) stage))
    (.addFirst netty-pipeline "incoming-traffic"
      (upstream-stage in))
    (.addFirst netty-pipeline "outgoing-traffic"
      (downstream-stage out))
    (.addLast netty-pipeline "outgoing-error"
      (downstream-stage error))
    (.addFirst netty-pipeline "incoming-error"
      (upstream-stage error))
    netty-pipeline))

;;;

(defn create-frame [frame delimiters strip-delimiters?]
  (cond
    (and frame delimiters) (delimited-frame delimiters frame)
    (and frame (not delimiters)) (compile-frame frame)
    (and (not frame) delimiters) (delimited-block delimiters (or strip-delimiters? true))
    :else nil))

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
	  (.write netty-channel msg (InetSocketAddress. ^String host (int port)))
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

(defn create-pipeline-factory
  [^ChannelGroup channel-group options
   connections-probe refuse-connections?
   pipeline-fn & args]
  (let [connection-count (atom 0)]
    (reify ChannelPipelineFactory
      (getPipeline [_]
	(let [pipeline ^ChannelPipeline (apply pipeline-fn args)]
	  (.addFirst pipeline
	    "channel-listener"
	    (if (and refuse-connections? @refuse-connections?)
	      (refuse-connection-stage)
	      (upstream-stage
		(fn [evt]
		  (when-let [ch ^Channel (channel-event evt)]
		    (if (.isOpen ch)
		      (when (.add channel-group ch)
			(let [origin (channel-origin ch)
			      connections (swap! connection-count inc)]
			  (trace connections-probe
			    {:event :opened
			     :connections connections
			     :address origin})
			  (run-pipeline (.getCloseFuture ch)
			    wrap-netty-channel-future
			    (fn [_]
			      (let [connections (swap! connection-count dec)]
				(trace connections-probe
				  {:event :closed
				   :connections connections
				   :address origin}))))))))
		  nil))))
	  pipeline)))))

(defn graceful-shutdown [server timeout]
  (let [connections (server-probe server :connections)
	num-connections #(dec (count (netty-channels server)))]
    (let [ready-to-close (if (zero? (num-connections))
			   (run-pipeline true)
			   (run-pipeline connections
			     read-channel
			     (fn [_]
			       (let [connections (num-connections)]
				 (trace [(name server) :shutdown :pending]
				   {:name (name server)
				    :connections connections})
				 (when (pos? connections)
				   (restart))))))]
      (run-pipeline (-> ready-to-close (poll-result timeout) read-channel)
	(fn [result]
	  (stop-server-immediately server))))))

(defn start-server
  "Starts a server.  Returns a function that stops the server."
  [pipeline-fn options]
  (let [options (merge
		  {:name (gensym "server.")}
		  options)
	refuse-connections? (atom false)
	port (:port options)
	channel-factory (NioServerSocketChannelFactory.
			  (Executors/newCachedThreadPool)
			  (Executors/newCachedThreadPool))
	server (ServerBootstrap. channel-factory)
	channel-group (DefaultChannelGroup.)]
    ;; connect server probes
    (siphon-probes (:name options) (:probes options))

    ;; set netty flags
    (doseq [[k v] (merge default-server-options (:netty options))]
      (.setOption server k v))

    ;; set pipeline factory
    (.setPipelineFactory server
      (create-pipeline-factory
	channel-group
	options
	(canonical-probe [(:name options) :connections])
	refuse-connections?
	pipeline-fn))

    ;; add parent channel to channel-group
    (.add channel-group (.bind server (InetSocketAddress. port)))

     ;; create server instance
    (reify AlephServer
      (stop-server-immediately [_]
	(trace [(:name options) :shutdown]
	  {:name options})
	(run-pipeline
	  (.close channel-group)
	  wrap-netty-channel-group-future
	  (fn [_]
	    (future (.releaseExternalResources server)))))
      (stop-server [this timeout]
	(reset! refuse-connections? true)
	(graceful-shutdown this timeout))
      (server-probe [_ probe-name]
	(probe-channel [(:name options) probe-name]))
      (netty-channels [_]
	(set channel-group))
      clojure.lang.IFn
      (invoke [this]
	(stop-server-immediately this))
      clojure.lang.Named
      (getName [_]
	(:name options))
      (getNamespace [_]
	nil))))

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
		   (Executors/newCachedThreadPool)
		   (Executors/newCachedThreadPool)))]

    ;; setup client probes
    (siphon-probes (:name options) (:probes options))

    ;; set netty flags
    (doseq [[k v] (merge default-client-options (:netty options))]
      (.setOption client k v))

    ;; set pipeline factory
    (.setPipelineFactory client
      (create-pipeline-factory
	channel-group
	options
	(canonical-probe [(:name options) :connections])
	nil
	pipeline-fn
	outer))

    ;; intialize client
    (run-pipeline (.connect client (InetSocketAddress. ^String host (int port)))
      :error-handler (fn [_])
      wrap-netty-channel-future
      (fn [^Channel netty-channel]

	;; write queue 
	(let [write-queue (create-write-queue
			    netty-channel
			    #(write-to-channel netty-channel nil true))]
	  (run-pipeline (.getCloseFuture netty-channel)
	    wrap-netty-channel-future
	    (fn [_]
	      (close inner)
	      (close outer)
	      (run-pipeline
		(.close channel-group)
		wrap-netty-channel-group-future
		(fn [_] (future (.releaseExternalResources client))))))
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


