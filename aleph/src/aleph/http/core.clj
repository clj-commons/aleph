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
     ChannelBuffer
     ChannelBuffers]
    [java.net
     URI]
    [java.nio.charset
     Charset]))

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
  [^ChannelBuffer body headers]
  (let [content-type (or (headers "content-type") "text/plain")
	charset (or (get headers "charset") "UTF-8")]
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

(defn transform-aleph-body
  [body headers]
  (let [content-type (or (get headers "content-type") "text/plain")
	charset (or (get headers "charset") "UTF-8")]
    (cond

	
      (string? body)
      (-> body (string->byte-buffer charset) byte-buffer->channel-buffer)
	
      (= content-type "application/json")
      (transform-aleph-body (with-out-str (write-json body (java.io.PrintWriter. *out*))) headers)
	
      :else
      (byte-buffer->channel-buffer body))))

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

(defn final-netty-message? [response]
  (or
    (and (instance? HttpChunk response) (.isLast ^HttpChunk response))
    (and (instance? HttpMessage response) (not (.isChunked ^HttpMessage response)))))

(defn transform-netty-response [^HttpResponse response headers]
  {:status (-> response .getStatus .getCode)
   :headers headers
   :body (transform-netty-body (.getContent response) headers)})
