;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.http.websocket
  (:use
    [lamina.core]
    [aleph.http core]
    [aleph.http.server requests responses]
    [aleph formats netty])
  (:require
    [clojure.string :as str])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     DefaultHttpResponse
     DefaultHttpRequest
     HttpVersion
     HttpResponseStatus
     HttpChunkAggregator]
    [org.jboss.netty.handler.codec.http.websocketx
     WebSocketFrame
     CloseWebSocketFrame
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     WebSocketServerHandshaker
     WebSocketServerHandshakerFactory]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [org.jboss.netty.channel
     Channel
     ChannelHandlerContext
     ChannelUpstreamHandler]))

(set! *warn-on-reflection* true)

(defn create-handshaker [^HttpRequest req]
  (when (= "websocket" (str/lower-case (.getHeader req "Upgrade")))
    (->
      (WebSocketServerHandshakerFactory.
        (str "ws://" (.getHeader req "Host") (.getUri req))
        nil
        false)
      (.newHandshaker req))))

(defn automatic-reply [^Channel ch ^WebSocketServerHandshaker handshaker ^WebSocketFrame frame]
  (cond
    (instance? CloseWebSocketFrame frame)
    (do
      (.close handshaker ch frame)
      true)

    (instance? PingWebSocketFrame frame)
    (do
      (.write ch (PongWebSocketFrame. (.getBinaryData frame)))
      true)

    :else
    false))

(defn unwrap-frame [^WebSocketFrame frame]
  (if (instance? TextWebSocketFrame frame)
    (.getText ^TextWebSocketFrame frame)
    (.getBinaryData frame)))

(defn wrap-frame [x]
  (if (string? x)
    (TextWebSocketFrame. ^String x)
    (BinaryWebSocketFrame. (bytes->channel-buffer x))))

(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)
	inner (wrap-write-channel inner)
        latch (atom false)
        handshaker (atom nil)]

    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
	(let [netty-channel (.getChannel ctx)]
          (if-let [msg (message-event evt)]
           (if (compare-and-set! latch false true)
             (if-let [h (create-handshaker msg)]
               (do
                 (reset! handshaker h)
                 (-> ctx .getPipeline (.addFirst "workaround" (HttpChunkAggregator. 1)))
                 (.handshake ^WebSocketServerHandshaker @handshaker netty-channel msg)

                 ;;
                 (receive-all outer
                   (fn [[returned-result msg]]
                     (when msg
                       (siphon-result
                         (write-to-channel netty-channel (wrap-frame msg) false)
                         returned-result))))


                 ;;
                 (run-pipeline (.getCloseFuture netty-channel)
                   wrap-netty-channel-future
                   (fn [_]
                     (close inner)
                     (close outer)))


                 (on-drained outer #(.close netty-channel))
                 
                 ;;
                 (handler
                   inner
                   (assoc (transform-netty-request msg netty-channel options)
                     :websocket true))
                 )
               (.sendUpstream ctx evt))
             (if @handshaker
               (when-not (automatic-reply netty-channel @handshaker msg)
                 (enqueue outer (unwrap-frame msg)))
               (.sendUpstream ctx evt)))

           (.sendUpstream ctx evt)))))))
