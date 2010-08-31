;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.core
  (:use
    [aleph netty formats]
    [aleph.http utils]
    [clojure.contrib.json])
  (:import
    [org.jboss.netty.channel
     ChannelFutureListener
     Channels]
    [org.jboss.netty.handler.codec.http
     DefaultHttpResponse
     DefaultHttpRequest
     HttpResponseStatus
     HttpMethod
     HttpVersion
     HttpHeaders
     HttpRequest
     HttpResponse
     HttpMessage
     HttpChunk]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [org.jboss.netty.handler.codec.http.websocket
     DefaultWebSocketFrame
     WebSocketFrame]
    [java.net
     URI]
    [java.nio.charset
     Charset]
    [java.security
     MessageDigest]))

;;;

(def request-methods
  {:get HttpMethod/GET
   :put HttpMethod/PUT
   :delete HttpMethod/DELETE
   :post HttpMethod/POST
   :trace HttpMethod/TRACE
   :connect HttpMethod/CONNECT
   :options HttpMethod/OPTIONS
   :head HttpMethod/HEAD})

;;;

(defn netty-headers
  "Get headers from Netty message."
  [^HttpMessage req]
  (let [headers (into {} (.getHeaders req))]
    (into {}
      (map
	(fn [[^String k v]] [(.toLowerCase k) v])
	(into {} (.getHeaders req))))))

(defn transform-netty-body
  "Transform body from ChannelBuffer into something more appropriate."
  [body headers]
  (let [content-type (or (headers "content-type") "text/plain")
	charset (or (headers "charset") "UTF-8")]
    (when-not (zero? (.readableBytes body))
      (cond

       (.startsWith content-type "text")
       (.toString body charset)

       (= content-type "application/json")
       (let [s (.toString body charset)]
	 (when-not (empty? s)
	   (read-json s true false nil)))

       :else
       (channel-buffer->input-stream body)))))

;;;

(defn call-error-handler
  "Calls the error-handling function."
  [options e]
  ((or (:error-handler options) #(.printStackTrace %)) e))

(defn response-listener
  "Handles the completion of the response."
  [options]
  (reify ChannelFutureListener
    (operationComplete [_ future]
      (when (:close? options)
	(Channels/close (.getChannel future)))
      (when-not (.isSuccess future)
	(call-error-handler options (.getCause future))))))

;;;

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
  (.addHeader response "sec-websocket-origin" (headers "origin"))
  (.addHeader response "sec-websocket-location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "sec-websocket-protocol")]
    (.addHeader response "sec-websocket-protocol" protocol))
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
  (.addHeader response "websocket-origin" (headers "origin"))
  (.addHeader response "websocket-location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "websocket-protocol")]
    (.addHeader response "websocket-protocol" protocol)))

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

;;;

(defn final-netty-message? [response]
  (or
    (and (instance? HttpChunk response) (.isLast ^HttpChunk response))
    (and (instance? HttpMessage response) (not (.isChunked ^HttpMessage response)))))

(defn transform-aleph-request [scheme ^String host ^Integer port request]
  (let [request (wrap-client-request request)
	uri (URI. scheme nil host port (:uri request) (:query-string request) (:fragment request))
        req (DefaultHttpRequest.
	      HttpVersion/HTTP_1_1
	      (request-methods (:request-method request))
	      (str
		(when-not (= \/ (-> uri .getPath first))
		  "/")
		(.getPath uri)
		(when-not (empty? (.getQuery uri))
		  "?")
		(.getQuery uri)))]
    (.setHeader req "host" (str host ":" port))
    (.setHeader req "accept-encoding" "gzip")
    (.setHeader req "connection" "keep-alive")
    (doseq [[k v-or-vals] (:headers request)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (.addHeader req (to-str k) v-or-vals)
	  (doseq [val v-or-vals]
	    (.addHeader req (to-str k) val)))))
    (when-let [body (:body request)]
      (.setContent req (input-stream->channel-buffer body)))
    req))

(defn transform-netty-response [^HttpResponse response headers]
  {:status (-> response .getStatus .getCode)
   :headers headers
   :body (transform-netty-body (.getContent response) headers)})
