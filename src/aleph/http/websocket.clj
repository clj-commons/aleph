;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket
  (:use
    [lamina.core])
  (:require
    [aleph.formats :as formats]
    [aleph.netty :as netty]
    [aleph.http.core :as http]
    [aleph.netty.client :as client]
    [clojure.string :as str])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpRequestEncoder
     HttpResponseDecoder]
    [org.jboss.netty.handler.codec.http.websocketx
     WebSocketFrame
     CloseWebSocketFrame
     PingWebSocketFrame
     PongWebSocketFrame
     TextWebSocketFrame
     BinaryWebSocketFrame
     WebSocketVersion
     WebSocketServerHandshaker
     WebSocketServerHandshakerFactory
     WebSocketClientHandshaker
     WebSocketClientHandshakerFactory]
    [org.jboss.netty.channel
     Channel
     ChannelHandlerContext
     ChannelDownstreamHandler
     ChannelUpstreamHandler]
    [org.jboss.netty.buffer
     ChannelBuffers]))

;;;

(defn unwrap-frame [^WebSocketFrame frame]
  (if (instance? TextWebSocketFrame frame)
    (.getText ^TextWebSocketFrame frame)
    (.getBinaryData frame)))

(defn wrap-frame [x]
  (if (string? x)
    (TextWebSocketFrame. ^String x)
    (BinaryWebSocketFrame. (formats/bytes->channel-buffer x))))

(defn wrap-websocket-channel [ch]
  (let [ch* (channel)]
    (join (map* wrap-frame ch*) ch)
    (splice
      (map* unwrap-frame ch)
      ch*)))

;;;

(defn ^WebSocketServerHandshaker create-server-handshaker [^HttpRequest req]
  (when (= "websocket" (str/lower-case (or (.getHeader req "Upgrade") "")))
    (->
      (WebSocketServerHandshakerFactory.
        (str "ws://" (.getHeader req "Host") (.getUri req))
        nil
        false)
      (.newHandshaker req))))

(defn server-automatic-reply
  [^Channel ch
   ^WebSocketServerHandshaker handshaker
   ^WebSocketFrame frame]
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

(defn server-handshake-stage [handshake-handler handler]
  (let [latch (atom false)
        handshaker (atom nil)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

        (let [netty-channel (.getChannel evt)]
          
          ;; if it's a message
          (if-let [msg (netty/event-message evt)]
            
            ;; if we haven't tried received a potential handshake yet
            (if-let [h (and
                         (compare-and-set! latch false true)
                         (create-server-handshaker msg))]
              (let [req (assoc (http/netty-request->ring-map {:msg msg})
                          :websocket true ;; deprecated
                          :websocket? true)]
                (run-pipeline (if handshake-handler
                                (let [ch (result-channel)]
                                  (handshake-handler ch req)
                                  ch)
                                {:status 101})
                  (fn [{:keys [status] :as rsp}]
                    (if-not (= 101 status)

                      ;; we've rejected the request, send the response and close the connection
                      (run-pipeline (.write netty-channel (:msg (http/ring-map->netty-response rsp)))
                        {:error-handler (fn [_])}
                        netty/wrap-netty-channel-future
                        (fn [_]
                          (.close netty-channel)))

                      ;; proceed with the handshake
                      (do
                        (reset! handshaker h)
                        (run-pipeline (.handshake ^WebSocketServerHandshaker @handshaker netty-channel msg)
                          netty/wrap-netty-channel-future
                          (fn [_]
                            (-> ctx
                              .getPipeline
                              (.replace "handler" "handler"
                                (netty/server-message-handler
                                  (fn [ch _]
                                    (handler
                                      (wrap-websocket-channel ch)
                                      req))
                                  netty-channel))))))))))
              
              (when-not (and
                          @handshaker
                          (server-automatic-reply netty-channel @handshaker msg))
                (.sendUpstream ctx evt)))
            
            (when @latch
              (.sendUpstream ctx evt))))))))

;;;

(defn client-handshake-stage [result url]
  (let [handshake-latch (atom false)
        response-latch (atom false)
        handshaker (.newHandshaker
                     (WebSocketClientHandshakerFactory.)
                     url
                     WebSocketVersion/V13
                     nil
                     false
                     nil)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
        (let [netty-channel (.getChannel evt)]

          ;; send out handshake
          (when (and
                  (.isConnected netty-channel)
                  (compare-and-set! handshake-latch false true))
            (.handshake handshaker netty-channel))

          ;; handle response
          (let [msg (netty/event-message evt)]
            (if (and msg (compare-and-set! response-latch false true))
              (run-pipeline nil
                {:result result
                 :error-handler (fn [_])}
                (fn [_]
                  (.finishHandshake handshaker netty-channel msg)
                  (-> ctx
                    .getPipeline
                    (.remove "handshaker"))
                  true))
              (.sendUpstream ctx evt))))))))

(defn websocket-client [options]
  (let [options (client/expand-client-options options)
        client-name (or
                      (:name options)
                      (-> options :client :name)
                      "websocket-client")
        result (result-channel)]
    (run-pipeline nil
      {:error-handler (fn [_])}
      (fn [_]
        (client/create-client
          client-name
          (fn [channel-group]
            (netty/create-netty-pipeline client-name nil channel-group
              :encoder (HttpRequestEncoder.)
              :decoder (HttpResponseDecoder.)
              :handshaker (client-handshake-stage
                            result
                            (java.net.URI. (client/options->url options)))))
          options))
      #(merge-results result %)
      second
      wrap-websocket-channel)))
