;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty.client
  (:use
    [lamina core trace]
    [aleph.netty core])
  (:import
    [java.util.concurrent
     Executors]
    [org.jboss.netty.channel
     ChannelPipeline
     ChannelUpstreamHandler
     ChannelEvent]
    [org.jboss.netty.channel.group
     DefaultChannelGroup]
    [org.jboss.netty.channel.socket.nio
     NioClientSocketChannelFactory]
    [org.jboss.netty.bootstrap
     ClientBootstrap]
    [org.jboss.netty.handler.ssl
     SslHandler]
    [java.net
     InetSocketAddress]))

;;;

(defn options->port [options]
  (let [port (or
               (:port options)
               (:server-port options))]
    (if (= port -1)
      (case (:scheme options)
        "http"  80
        "https" 443)
      port)))

(defn url->options [url]
  (when url
    (let [url (java.net.URL. url)]
      {:scheme (.getProtocol url)
       :server-name (.getHost url)
       :server-port (.getPort url)
       :uri (.getPath url)
       :user-info (.getUserInfo url)
       :query-string (.getQuery url)})))

(defn expand-client-options [options]
  (let [options (merge
                  options
                  (url->options (:url options)))]
    (-> options
      (assoc :port (options->port options))
      (dissoc :url))))

;;;

(def default-netty-client-options
  {"tcpNoDelay" true,
   "reuseAddress" true,
   "readWriteFair" true,
   "connectTimeoutMillis" 3000})

(def channel-factory
  (delay
    (NioClientSocketChannelFactory. 
      (Executors/newCachedThreadPool)
      (Executors/newCachedThreadPool))))

(defn client-message-handler [ch options]
  (let [latch (atom false)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

        ;; handle initial setup
        (when (and
                (instance? ChannelEvent evt)
                (compare-and-set! latch false true))
          (let [netty-channel (.getChannel evt)]

            (.set local-options options)
            (.set local-channel netty-channel)

            ;; set up write handling
            (receive-all ch
              #(wrap-netty-channel-future (.write netty-channel %)))

            ;; lamina -> netty
            (on-drained ch
              #(.close netty-channel))

            ;; netty -> lamina
            (run-pipeline (.getCloseFuture netty-channel)
              wrap-netty-channel-future
              (fn [_]
                (close ch)))))

        ;; handle messages
        (if-let [msg (event-message evt)]
          (enqueue ch msg)
          (.sendUpstream ctx evt))))))

(defn create-ssl-handler
  [{:keys [server-name server-port]}]
  (SslHandler.
    (doto
      (.createSSLEngine
        (javax.net.ssl.SSLContext/getDefault)
        server-name
        server-port)
      (.setUseClientMode true))))

(defn create-client
  [client-name pipeline-generator options]
  (let [options (expand-client-options options)
        ssl? (= "https" (:scheme options))]
    
    (doseq [[k v] (:probes options)]
      (siphon (probe-channel [client-name k]) v))

    (let [[a b] (channel-pair)
          channel-group (DefaultChannelGroup.)
          client (ClientBootstrap. @channel-factory)
          ^String host (or (:server-name options) (:host options))
          port (int (:port options))]

      (doseq [[k v] (merge default-netty-client-options (-> options :netty :options))]
        (.setOption client k v))

      (.setPipelineFactory client
        (create-pipeline-factory
          channel-group
          (fn [channel-group]
            (let [^ChannelPipeline pipeline (pipeline-generator channel-group)]
              (.addLast pipeline "handler" (client-message-handler a options))
              (when ssl?
                (.addFirst pipeline "ssl" (create-ssl-handler options)))
              pipeline))))

      (run-pipeline (.connect client (InetSocketAddress. host port))
        {:error-handler (fn [_])}
        wrap-netty-channel-future
        (fn [netty-channel]
          (.add channel-group netty-channel)
          b)))))
