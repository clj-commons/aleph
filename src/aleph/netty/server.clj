;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty.server
  (:use
    [lamina core trace]
    [aleph.netty core])
  (:require
    [clojure.tools.logging :as log])
  (:import
    [org.jboss.netty.channel
     Channel
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

(set! *warn-on-reflection* true)

;;;

(def default-server-options
  {"child.reuseAddress" true,
   "reuseAddress" true,
   "child.keepAlive" true,
   "child.connectTimeoutMillis" 100,
   "tcpNoDelay" true,
   "readWriteFair" true,
   "child.tcpNoDelay" true})

(defn start-server [server-name pipeline-generator options]
  (let [{:keys [port host]} options
        channel-factory (NioServerSocketChannelFactory.
                          (cached-thread-executor options)
                          (cached-thread-executor options))
        channel-group (DefaultChannelGroup.)
        server (ServerBootstrap. channel-factory)
        close-result (result-channel)
        pipeline-generator (if-let [transform (-> options :netty :pipeline-transform)]
                             (comp transform pipeline-generator)
                             pipeline-generator)]

    (doseq [[k v] (:probes options)]
      (run-pipeline close-result (fn [_] (close v)))
      (siphon (probe-channel [server-name k]) v))
    
    (.setPipelineFactory server
      (create-pipeline-factory channel-group pipeline-generator))

    (doseq [[k v] (merge default-server-options (-> options :netty :options))]
      (.setOption server k v))

    (.add channel-group
      (.bind server
        (if host
          (InetSocketAddress. ^String host (long port))
          (InetSocketAddress. (long port)))))

    (fn []
      (let [close-future (.close channel-group)]
        (future
          (.awaitUninterruptibly close-future)
          (.releaseExternalResources server)
          (success close-result true))))))

;;;

(defn server-message-handler
  ([handler]
     (server-message-handler handler nil))
  ([handler netty-channel]
     (let [[a b] (channel-pair)
           latch (atom false)
           initializer (fn [^Channel netty-channel]
                         (when (compare-and-set! latch false true)
                        
                           (on-error a
                             (fn [ex]
                               (log/error ex "Error in server handler, closing connection.")
                               (close-channel netty-channel)))
                           
                           ;; set up write handling
                           (receive-all a
                             #(wrap-netty-channel-future (.write netty-channel %)))
                           
                           ;; lamina -> netty
                           (on-drained a
                             #(close-channel netty-channel))
                           
                           ;; netty -> lamina
                           (run-pipeline (.getCloseFuture netty-channel)
                             wrap-netty-channel-future
                             (fn [_]
                               (close a)
                               (close b)))

                           ;; call handler
                           (let [remote-address (channel-remote-host-address netty-channel)]
                             (handler
                               (wrap-network-channel netty-channel b)
                               {:address remote-address}))))

           ;; if we already have the channel, just initialize it now
           initializer (if netty-channel
                         (do
                           (initializer netty-channel)
                           nil)
                         initializer)]

       (reify ChannelUpstreamHandler
         (handleUpstream [_ ctx evt]

           (let [netty-channel (.getChannel ctx)]

             (.set local-channel netty-channel)

             (try

               (when initializer
                 (initializer netty-channel))
        
               ;; handle messages
               (if-let [msg (event-message evt)]
                 (enqueue a msg)
                 (.sendUpstream ctx evt))

               ;; don't hold onto channel
               (finally
                 #_(.set local-channel nil)))))))))

