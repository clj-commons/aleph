;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket.hybi
  (:use
    [lamina core]
    [gloss io]
    [aleph formats netty]
    [aleph.http.websocket protocol])
  (:import
    [org.jboss.netty.channel
     Channel
     ChannelHandlerContext
     ChannelUpstreamHandler]))

(def handshake-magic-string "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn websocket-response [request]
  (let [key (get-in request [:headers "sec-websocket-key"])]
    {:status 101
     :headers {"Sec-WebSocket-Accept"
	       (-> (str key handshake-magic-string) bytes->sha1 base64-encode)}}))

(defn websocket-handler [handler ^Channel netty-channel handshake]
  (let [in (channel)
	out (channel)]

    ;; handle outgoing messages
    (receive-all out
      (fn [[returned-result msg]]
	(when msg
	  (siphon-result
	    (write-to-channel netty-channel (bytes->channel-buffer (encode-frame msg)) false)
	    returned-result))))

    (on-closed out #(.close netty-channel))

    (run-pipeline (.getCloseFuture netty-channel)
      wrap-netty-channel-future
      (fn [_]
	(close in)
	(close out)))

    (handler
      (splice
	(wrap-websocket-channel in)
	(wrap-write-channel out))
      handshake)

    (message-stage
      (fn [_ msg]
	(enqueue in (bytes->byte-buffers msg))))))

(defn update-pipeline [handler ^ChannelHandlerContext ctx ^Channel channel handshake response]
  (let [channel (.getChannel ctx)
	pipeline (.getPipeline channel)]
    (.replace pipeline "websocket-handshake" "websocket" (websocket-handler handler channel handshake))
    (.remove pipeline "decoder")
    (write-to-channel channel response false)
    (.remove pipeline "encoder")))
