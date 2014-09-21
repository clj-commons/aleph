;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty.udp
  (:use
    [lamina core trace])
  (:require
    [aleph.formats :as formats]
    [aleph.netty.core :as netty]
    [gloss.core :as gloss]
    [gloss.io :as gloss-io]
    [clojure.tools.logging :as log])
  (:import
    [java.util.concurrent
     Executors]
    [org.jboss.netty.bootstrap
     ConnectionlessBootstrap]
    [org.jboss.netty.channel.socket.nio
     NioDatagramChannelFactory]
    [org.jboss.netty.channel.socket
     DatagramChannel]
    [java.net
     InetSocketAddress]
    [org.jboss.netty.channel
     Channel
     ChannelPipeline
     ChannelUpstreamHandler
     FixedReceiveBufferSizePredictor]
    [java.net
     InetAddress
     InetSocketAddress]))

(def channel-factory
  (delay
    (NioDatagramChannelFactory. 
      (Executors/newCachedThreadPool))))

(defn udp-message-handler [ch options]
  (let [latch (atom false)
        encoder (formats/options->encoder options)
        decoder (formats/options->decoder options)
        auto-encode? (:auto-encode? options)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

        (let [netty-channel (.getChannel ctx)]

          ;; handle initial setup
          (when (compare-and-set! latch false true)

            (on-error ch
              (fn [ex]
                (log/error ex)
                (.close netty-channel)))

            ;; set up write handling
            (receive-all ch
              (fn [{:keys [host port message]}]
                (let [message (if encoder
                                (gloss-io/encode encoder message)
                                message)]
                  (netty/wrap-netty-channel-future
                    (.write netty-channel
                      (if auto-encode?
                        (formats/bytes->channel-buffer message)
                        message)
                      (InetSocketAddress. ^String host (int port)))))))
            
            ;; lamina -> netty
            (on-drained ch
              #(.close netty-channel))
            
            ;; netty -> lamina
            (run-pipeline (.getCloseFuture netty-channel)
              netty/wrap-netty-channel-future
              (fn [_]
                (close ch)))))
        
        ;; handle messages
        (if-let [msg (netty/event-message evt)]
          (let [^InetSocketAddress origin (netty/event-remote-address evt)
                host (.getHostAddress (.getAddress origin))
                port (.getPort origin)
                msg (if decoder
                      (gloss-io/decode decoder (formats/bytes->byte-buffers msg))
                      msg)]
            (enqueue ch {:message msg, :host host, :port port}))
          (.sendUpstream ctx evt))))))

(defn create-udp-socket
  [socket-name pipeline-generator options]

  (let [[a b] (channel-pair)
        client (ConnectionlessBootstrap. @channel-factory)
        {:keys [port broadcast? buf-size]
         :or {port 0
              buf-size 16384}} options
        netty-options (-> options
                        :netty
                        :options
                        (update-in ["broadcast"]
                          #(or % broadcast?))
                        (update-in ["receiveBufferSize"]
                          #(or % buf-size))
                        (update-in ["sendBufferSize"]
                          #(or % buf-size))
                        (update-in ["receiveBufferSizePredictor"]
                          #(or % (when buf-size (FixedReceiveBufferSizePredictor. buf-size)))))]

    (doseq [[k v] netty-options]
      (when v
        (.setOption client k v)))

    (doseq [[k v] (:probes options)]
      (on-closed b #(close v))
      (siphon (probe-channel [socket-name k]) v))

    (.setPipelineFactory client
      (netty/create-pipeline-factory nil
        (fn [_]
          (let [^ChannelPipeline pipeline (pipeline-generator nil)]
            (.addLast pipeline "handler" (udp-message-handler a options))
            pipeline))))

    (run-pipeline (.bind client (InetSocketAddress. port))
      {:error-handler (fn [_])}
      (fn [netty-channel]
        b))))

