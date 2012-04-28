;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty.core
  (:use
    [lamina core trace]
    [potemkin])
  (:require
    [aleph.formats :as formats]
    [clojure.tools.logging :as log])
  (:import
    [org.jboss.netty.buffer
     ChannelBuffer]
    [org.jboss.netty.channel.group
     DefaultChannelGroup
     ChannelGroup]
    [java.util.concurrent
     ThreadFactory
     Executors]
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
    [org.jboss.netty.channel
     Channel]
    [java.net
     SocketAddress
     InetSocketAddress
     InetAddress]))

(set! *warn-on-reflection* true)

;;;

(def ^ThreadLocal local-options (ThreadLocal.))

(def ^ThreadLocal local-channel (ThreadLocal.))

(defn current-options []
  (.get local-options))

(defn ^Channel current-channel []
  (.get local-channel))

(defn cached-thread-executor [options]
  (Executors/newCachedThreadPool
    (reify ThreadFactory
      (newThread [_ r]
        (Thread.
          (fn []
            (.set local-options options)
            (.run r)))))))

;;;

(defn channel-remote-host-address [^Channel channel]
  (when-let [socket-address (.getRemoteAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-host-address [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-port [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (.getPort ^InetSocketAddress socket-address)))

(defmacro def-event-accessor [name type accessor]
  `(defn ~name [~(with-meta 'evt {:tag type})]
     (when (instance? ~type ~'evt)
       (~accessor ~'evt))))

(def-event-accessor event-message MessageEvent .getMessage)
(def-event-accessor event-remote-address MessageEvent .getRemoteAddress)
(def-event-accessor event-exception ExceptionEvent .getCause)

(defn wrap-netty-channel-future
  [^ChannelFuture netty-future]
  (let [ch (result-channel)]
    (.addListener netty-future
      (reify ChannelFutureListener
	(operationComplete [_ netty-future]
	  (if (.isSuccess netty-future)
	    (success ch (.getChannel netty-future))
	    (error ch (.getCause netty-future)))
	  nil)))
    ch))

;;;

(defn ^ChannelUpstreamHandler upstream-error-handler [pipeline-name error-predicate]
  (let [error-probe (error-probe-channel [pipeline-name :error])
        error-predicate (or error-predicate (constantly true))]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
        (if-let [error (event-exception evt)]
          (when (error-predicate error)
            (enqueue error-probe error))
          (.sendUpstream ctx evt))))))

(defn ^ChannelDownstreamHandler downstream-error-handler [pipeline-name error-predicate]
  (let [error-probe (error-probe-channel [pipeline-name :error])
        error-predicate (or error-predicate (constantly true))]
    (reify ChannelDownstreamHandler
      (handleDownstream [_ ctx evt]
        (if-let [error (event-exception evt)]
          (when (error-predicate error)
            (enqueue error-probe error))
          (.sendDownstream ctx evt))))))

(defn connection-handler [pipeline-name ^ChannelGroup channel-group]
  (let [open? (atom false)
        closed? (atom true)
        connection-probe (probe-channel [pipeline-name :connections])]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
        (when (instance? ChannelEvent evt)
          (let [channel-open? (-> ^ChannelEvent evt .getChannel .isOpen)]
            (cond
              
              (and
                channel-open?
                (compare-and-set! open? false channel-open?))
              (do
                (.add channel-group (.getChannel ^ChannelEvent evt))
                (enqueue connection-probe (dec (count channel-group))))

              (and
                (not channel-open?)
                @open? 
                (compare-and-set! closed? true channel-open?))
              (enqueue connection-probe (dec (count channel-group))))))
        (.sendUpstream ctx evt)))))

(defn upstream-traffic-handler [pipeline-name]
  (let [traffic-probe (probe-channel [pipeline-name :traffic :in])]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
        (when-let [^ChannelBuffer msg (event-message evt)]
          (enqueue traffic-probe
            {:address (event-remote-address evt)
             :bytes (.readableBytes msg)}))
        (.sendUpstream ctx evt)))))

(defn downstream-traffic-handler [pipeline-name]
  (let [traffic-probe (probe-channel [pipeline-name :traffic :out])]
    (reify ChannelDownstreamHandler
      (handleDownstream [_ ctx evt]
        (when-let [^ChannelBuffer msg (event-message evt)]
          (enqueue traffic-probe
            {:address (event-remote-address evt)
             :bytes (.readableBytes msg)}))
        (.sendDownstream ctx evt)))))

(defmacro create-netty-pipeline
  [pipeline-name error-predicate channel-group & stages]
  (unify-gensyms
    `(let [pipeline## (Channels/pipeline)
           channel-group# ~channel-group
           error-predicate# ~error-predicate]
       ~@(map
           (fn [[stage-name stage]]
             `(.addLast pipeline## ~(name stage-name) ~stage))
           (partition 2 stages))

       ;; traffic probes
       (.addFirst pipeline## "incoming-traffic"
         (upstream-traffic-handler ~pipeline-name))
       (.addFirst pipeline## "outgoing-traffic"
         (downstream-traffic-handler ~pipeline-name))

       ;; connections
       (when channel-group#
         (.addFirst pipeline## "channel-group-handler"
           (connection-handler ~pipeline-name channel-group#)))

       ;; error logging
       #_(.addLast pipeline## "outgoing-error"
         (downstream-error-handler ~pipeline-name error-predicate#))
       #_(.addFirst pipeline## "incoming-error"
         (upstream-error-handler ~pipeline-name error-predicate#))

       pipeline##)))

(defn create-pipeline-factory [channel-group pipeline-generator]
  (reify ChannelPipelineFactory
    (getPipeline [_]
      (pipeline-generator channel-group))))


