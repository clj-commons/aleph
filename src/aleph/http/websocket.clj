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
    [aleph formats netty]
    [gloss.io])
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
  (DefaultWebSocketFrame. 0 (bytes->channel-buffer msg)))

(defn websocket-handshake? [^HttpRequest request]
  (let [connection-header (.getHeader request "connection") ]
    (and connection-header
         (= "upgrade" (.toLowerCase connection-header))
         (= "websocket" (.toLowerCase (.getHeader request "upgrade"))))))

(defn transform-key [k]
  (/
    (-> k (.replaceAll "[^0-9]" "") Long/parseLong)
    (-> k (.replaceAll "[^ ]" "") .length)))

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
     :body (md5-hash
	     (doto (ByteBuffer/allocate 16)
	       (.putInt (transform-key (headers "sec-websocket-key1")))
	       (.putInt (transform-key (headers "sec-websocket-key2")))
	       (.putLong (-> request :body .readLong))))}))

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

(defn websocket-response [^HttpRequest request netty-channel options]
  (.setHeader request "content-type" "application/octet-stream")
  (let [request (transform-netty-request request netty-channel options)
	headers (:headers request)
	response (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
		   (secure-websocket-response request)
		   (standard-websocket-response request))]
    (transform-aleph-response
      (update-in response [:headers]
	#(assoc %
	   "Upgrade" "WebSocket"
	   "Connection" "Upgrade"))
      options)))

(defn- respond-to-handshake [ctx ^HttpRequest request options]
  (let [channel (.getChannel ctx)
	pipeline (.getPipeline channel)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (write-to-channel channel (websocket-response request channel options) false)
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))))

;;TODO: uncomment out closing handshake, and add in timeout so that we're not waiting forever
(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)
	inner (wrap-write-channel inner)
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
		  (run-pipeline
		    (receive-in-order outer
		      (fn [[returned-result msg]]
			(when msg
			  (siphon-result
			    (write-to-channel ch (to-websocket-frame msg) false)
			    returned-result))))
		    (fn [_]
		      '(write-to-channel ch WebSocketFrame/CLOSING_HANDSHAKE (close?))
		      (.close ch)))
		  (respond-to-handshake ctx msg options)
		  (handler inner (assoc (transform-netty-request msg ch options) :websocket true)))
		(.sendUpstream ctx evt))))

	  (if-let [ch (channel-event evt)]
	    (when-not (.isConnected ch)
	      (enqueue-and-close inner nil)
	      (enqueue-and-close outer nil))
	    (.sendUpstream ctx evt)))))))
