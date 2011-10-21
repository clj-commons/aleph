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
    [gloss core io])
  (:require
    [aleph.http.websocket.hybi :as hybi]
    [aleph.http.websocket.hixie :as hixie])
  (:import
    
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
     Channel
     ChannelHandlerContext
     ChannelUpstreamHandler]))


(defn websocket-handshake? [^HttpRequest request]
  (let [connection-header (.getHeader request "connection") ]
    (and connection-header
         (= "upgrade" (.toLowerCase connection-header))
         (= "websocket" (.toLowerCase (.getHeader request "upgrade"))))))

(defn hybi? [^HttpRequest request]
  (.containsHeader request "Sec-WebSocket-Version"))

(defn transform-handshake [^HttpRequest request netty-channel options]
  (.setHeader request "content-type" "application/octet-stream")
  (assoc (transform-netty-request request netty-channel options)
    :websocket true))

(defn websocket-response [^HttpRequest netty-request netty-channel options]
  (let [request (transform-handshake netty-request netty-channel options)
	response (if (hybi? netty-request)
		   (hybi/websocket-response request)
		   (hixie/websocket-response request))]
    (transform-aleph-response
      (update-in response [:headers]
	#(assoc %
	   "Upgrade" "WebSocket"
	   "Connection" "Upgrade"))
      options)))

(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)
	inner (wrap-write-channel inner)]

    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
	
	(if-let [msg (message-event evt)]
	  
	  (let [ch ^Channel (.getChannel ctx)]
	    (if (websocket-handshake? msg)
	      (let [handshake (transform-handshake msg ch options)
		    response (websocket-response msg ch options)]
		(if (hybi? msg)
		  (hybi/update-pipeline handler ctx ch handshake response options)
		  (hixie/update-pipeline handler ctx ch handshake response options)))
	      (.sendUpstream ctx evt)))

	  (.sendUpstream ctx evt))))))
