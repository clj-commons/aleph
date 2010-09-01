;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket
  (:use
    [aleph.http core])
  (:import
    [org.jboss.netty.handler.codec.http.websocket
     DefaultWebSocketFrame
     WebSocketFrame]
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     DefaultHttpResponse
     HttpVersion
     HttpResponseStatus]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [java.security
     MessageDigest]))

(defn from-websocket-frame [^WebSocketFrame frame]
  (.getTextData frame))

(defn to-websocket-frame [msg]
  (DefaultWebSocketFrame. msg))

(defn websocket-handshake? [^HttpRequest request]
  (and
    (= "upgrade" (.toLowerCase (.getHeader request "connection")))
    (= "websocket" (.toLowerCase (.getHeader request "upgrade")))))

(defn transform-key [k]
  (/
    (-> k (.replaceAll "[^0-9]" "") Long/parseLong)
    (-> k (.replaceAll "[^ ]" "") .length)))

(defn secure-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "Sec-WebSocket-Origin" (headers "origin"))
  (.addHeader response "Sec-WebSocket-Location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "sec-websocket-protocol")]
    (.addHeader response "Sec-WebSocket-Protocol" protocol))
  (let [buf (ChannelBuffers/buffer 16)]
    (doto buf
      (.writeInt (transform-key (headers "sec-websocket-key1")))
      (.writeInt (transform-key (headers "sec-websocket-key2")))
      (.writeLong (-> request .getContent .readLong)))
    (.setContent response
      (-> (MessageDigest/getInstance "MD5")
	(.digest (.array buf))
	ChannelBuffers/wrappedBuffer))))

(defn standard-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "WebSocket-Origin" (headers "origin"))
  (.addHeader response "WebSocket-Location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "websocket-protocol")]
    (.addHeader response "WebSocket-Protocol" protocol)))

(defn websocket-response [^HttpRequest request]
  (let [response (DefaultHttpResponse.
		   HttpVersion/HTTP_1_1
		   (HttpResponseStatus. 101 "Web Socket Protocol Handshake"))
	headers (netty-headers request)]
    (.addHeader response "Upgrade" "WebSocket")
    (.addHeader response "Connection" "Upgrade")
    (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
      (secure-websocket-response request headers response)
      (standard-websocket-response request headers response))
    response))
