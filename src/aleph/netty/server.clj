;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty.server
  (:use
    [lamina core]
    [aleph.netty core])
  (:import
    [org.jboss.netty.channel
     ChannelUpstreamHandler
     ChannelEvent]
    [org.jboss.netty.channel.group
     DefaultChannelGroup]
    [org.jboss.netty.channel.socket.nio
     NioServerSocketChannelFactory]
    [org.jboss.netty.bootstrap
     ServerBootstrap]
    [java.net
     InetSocketAddress]))

;;;

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 100,
   "tcpNoDelay" true,
   "readWriteFair" true,
   "child.tcpNoDelay" true})

(defn start-server [pipeline-generator options]
  (let [port (:port options)
        channel-factory (NioServerSocketChannelFactory.
                          (cached-thread-executor options)
                          (cached-thread-executor options))
        channel-group (DefaultChannelGroup.)
        server (ServerBootstrap. channel-factory)]
    
    (.setPipelineFactory server
      (create-pipeline-factory channel-group pipeline-generator))

    (doseq [[k v] (merge default-server-options (-> options :netty :options))]
      (.setOption server k v))

    (.add channel-group (.bind server (InetSocketAddress. port)))

    (fn []
      (let [close-future (.close channel-group)]
        (future
          (.awaitUninterruptibly close-future)
          (.releaseExternalResources server))))))

;;;

(defn server-message-handler [handler]
  (let [[a b] (channel-pair)
        latch (atom false)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

        ;; handle initial setup
        (when (and
                (instance? ChannelEvent evt)
                (compare-and-set! latch false true))
          (let [netty-channel (.getChannel evt)]
            (handler b {:address (channel-origin netty-channel)})
            (receive-all a
              #(wrap-netty-channel-future (.write netty-channel %)))
            (on-drained a
              #(.close netty-channel))))

        ;; handle messages
        (if-let [msg (event-message evt)]
          (enqueue a msg)
          (.sendUpstream ctx evt))))))
