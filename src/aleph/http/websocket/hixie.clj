;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket.hixie
  (:use
    [lamina core executors]
    [aleph formats netty]
    [gloss core io])
  (:import
    [org.jboss.netty.handler.codec.http.websocket
     DefaultWebSocketFrame
     WebSocketFrame
     WebSocketFrameEncoder
     WebSocketFrameDecoder]
    [org.jboss.netty.channel
     Channel
     ChannelHandlerContext
     ChannelUpstreamHandler]))

;;;

(defn transform-key [^String k]
  (unchecked-divide
    (long (-> k (.replaceAll "[^0-9]" "") Long/parseLong))
    (long (-> k (.replaceAll "[^ ]" "") .length))))

(defcodec response-codec [:int32 :int32 :int64])
(defcodec request-codec :int64)

(defn secure-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {"Sec-WebSocket-Origin" (headers "origin")
	       "Sec-WebSocket-Location" (str "ws://"
					  (headers "host")
					  (:uri request)
					  (when (:query-string request)
					    (str "?" (:query-string request))))
	       "Sec-WebSocket-Protocol" (headers "sec-websocket-protocol")}
     :body (->> [(transform-key (headers "sec-websocket-key1"))
		 (transform-key (headers "sec-websocket-key2"))
		 (->> request :body bytes->byte-buffers (decode request-codec))]
	     (encode response-codec)
	     bytes->md5)}))

(defn standard-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {"WebSocket-Origin" (headers "origin")
	       "WebSocket-Location" (str "ws://"
				      (headers "host")
				      (:uri request)
				      (when (:query-string request)
					(str "?" (:query-string request))))
	       "WebSocket-Protocol" (headers "websocket-protocol")}}))

(defn websocket-response [request]
  (let [headers (:headers request)]
    (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
      (secure-websocket-response request)
      (standard-websocket-response request))))

;;;

(defn from-websocket-frame [^WebSocketFrame frame]
  (.getTextData frame))

(defn to-websocket-frame [msg]
  (DefaultWebSocketFrame. 0 (bytes->channel-buffer msg)))

(defn websocket-handler [handler ^Channel netty-channel handshake options]
  (let [[inner outer] (channel-pair)
	inner (wrap-write-channel inner)]

    ;; handle outgoing messages
    (receive-all outer
      (fn [[returned-result msg]]
	(when msg
	  (siphon-result
	    (write-to-channel netty-channel (to-websocket-frame msg) false)
	    returned-result))))

    (on-closed outer #(.close netty-channel))

    (run-pipeline (.getCloseFuture netty-channel)
      wrap-netty-channel-future
      (fn [_]
	(close inner)
	(close outer)))

    [#(handler inner handshake)
     
     (message-stage
       (fn [_ msg]
	 (let [msg (from-websocket-frame msg)]
           (enqueue outer msg))))]
    ))

(defn update-pipeline [handler ^ChannelHandlerContext ctx ^Channel channel handshake response options]
  (let [channel (.getChannel ctx)
	pipeline (.getPipeline channel)
	[handler stage] (websocket-handler handler channel handshake options)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (.replace pipeline "websocket-handshake" "websocket" ^ChannelUpstreamHandler stage)
    (write-to-channel channel response false)
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))
    (handler)))

