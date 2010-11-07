;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket
  (:use
    [lamina.core]
    [aleph.http core]
    [aleph formats netty])
  (:import
    [org.jboss.netty.handler.codec.http.websocket
     DefaultWebSocketFrame
     WebSocketFrame
     WebSocketFrameEncoder
     WebSocketFrameDecoder]
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     DefaultHttpResponse
     DefaultHttpRequest
     HttpVersion
     HttpResponseStatus]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [org.jboss.netty.channel
     ChannelUpstreamHandler]
    [java.nio
     ByteBuffer]
    [java.security
     MessageDigest]))

(defn md5-hash [buf]
  (->> buf
    .array
    (.digest (MessageDigest/getInstance "MD5"))
    ByteBuffer/wrap))

(defn from-websocket-frame [^WebSocketFrame frame]
  (.getTextData frame))

(defn to-websocket-frame [msg]
  (DefaultWebSocketFrame. 0 (to-channel-buffer msg)))

(defn websocket-handshake? [^HttpRequest request]
  (and
    (= "upgrade" (.toLowerCase (.getHeader request "connection")))
    (= "websocket" (.toLowerCase (.getHeader request "upgrade")))))

(defn transform-key [k]
  (/
    (-> k (.replaceAll "[^0-9]" "") Long/parseLong)
    (-> k (.replaceAll "[^ ]" "") .length)))

(defn secure-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {"Sec-WebSocket-Origin" (headers "origin")
	       "Sec-WebSocket-Location" (str "ws://" (headers "host") (:uri request))
	       "Sec-WebSocket-Protocol" (headers "sec-websocket-protocol")}
     :body (md5-hash
	     (doto (ByteBuffer/allocate 16)
	       (.putInt (transform-key (headers "sec-websocket-key1")))
	       (.putInt (transform-key (headers "sec-websocket-key2")))
	       (.putLong (-> request :body .getLong))))}))

(defn standard-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {"WebSocket-Origin" (headers "origin")
	       "WebSocket-Location" (str "ws://" (headers "host") (:uri request))
	       "WebSocket-Protocol" (headers "websocket-protocol")}}))

(defn websocket-response [^HttpRequest request]
  (.setHeader request "content-type" "application/octet-stream")
  (let [request (transform-netty-request request)
	headers (:headers request)
	response (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
		   (secure-websocket-response request)
		   (standard-websocket-response request))]
    (transform-aleph-response
      (update-in response [:headers]
	#(assoc %
	   "upgrade" "WebSocket"
	   "connection" "Upgrade")))))

(defn- respond-to-handshake [ctx ^HttpRequest request]
  (let [channel (.getChannel ctx)
	pipeline (.getPipeline channel)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (write-to-channel channel (websocket-response request) false)
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))))

;;TODO: uncomment out closing handshake, and add in timeout so that we're not waiting forever
(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)
	close-atom (atom false)
	close? #(not (compare-and-set! close-atom false true))]
    
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

	(if-let [msg (message-event evt)]

	  (let [ch (.getChannel ctx)]
	    (cond
	      (instance? WebSocketFrame msg)
	      (if (= msg WebSocketFrame/CLOSING_HANDSHAKE)
		(do
		  (enqueue-and-close outer nil)
		  '(when (close?)
		    (.close ch)))
		(enqueue outer (from-websocket-frame msg)))
	      
	      (instance? HttpRequest msg)
	      (if (websocket-handshake? msg)
		(do
		  (receive-all outer
		    (fn [msg]
		      (when msg
			(write-to-channel ch (to-websocket-frame msg) false))
		      (when (closed? outer)
			'(write-to-channel ch WebSocketFrame/CLOSING_HANDSHAKE (close?))
			(.close ch))))
		  (respond-to-handshake ctx msg)
		  (handler inner (assoc (transform-netty-request msg) :websocket true)))
		(.sendUpstream ctx evt))))
	  
	  (if-let [ch (channel-event evt)]
	    (when-not (.isConnected ch)
	      (enqueue-and-close inner nil)
	      (enqueue-and-close outer nil))
	    (.sendUpstream ctx evt)))))))
