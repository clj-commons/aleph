;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.core
  (:use
    [aleph netty formats core]
    [aleph.http utils])
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
    [java.io
     InputStreamReader]
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

(defn final-netty-message? [response]
  (or
    (and (instance? HttpChunk response) (.isLast ^HttpChunk response))
    (and (instance? HttpMessage response) (not (.isChunked ^HttpMessage response)))))

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
       (channel-buffer->string body charset)

       (= content-type "application/json")
       (-> body channel-buffer->input-stream InputStreamReader. from-json)

       :else
       (channel-buffer->byte-buffer body)))))

(defn transform-aleph-body
  [body headers]
  (let [content-type (or (get headers "content-type") "text/plain")
	charset (or (get headers "charset") "UTF-8")]
    (cond

      (= content-type "application/json")
      (to-channel-buffer (to-json body))

      (to-channel-buffer? body)
      (to-channel-buffer body)

      (sequential? body)
      (to-channel-buffer (to-json body))
	
      :else
      (throw (Exception. (str "Can't convert body: " body))))))

;;;

(defn- netty-request-method
  "Get HTTP method from Netty request."
  [^HttpRequest req]
  {:request-method (->> req .getMethod .getName .toLowerCase keyword)})

(defn- netty-request-uri
  "Get URI from Netty request."
  [^HttpRequest req]
  (let [paths (.split (.getUri req) "[?]")]
    {:uri (first paths)
     :query-string (second paths)}))

(defn transform-netty-request
  "Transforms a Netty request into a Ring request."
  [^HttpRequest req]
  (let [headers (netty-headers req)
	parts (.split (headers "host") "[:]")
	host (first parts)
	port (when-let [port (second parts)]
	       (Integer/parseInt port))]
    (merge
      (netty-request-method req)
      {:headers headers}
      {:body (let [body (transform-netty-body (.getContent req) headers)]
	       (if (final-netty-message? req)
		 body
		 (let [ch (channel)]
		   (when body
		     (enqueue ch body))
		   ch)))}
      {:keep-alive? (HttpHeaders/isKeepAlive req)
       :server-name host
       :server-port port}
      (netty-request-uri req))))

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
      (if (channel? body)
	(.setHeader req "transfer-encoding" "chunked")
	(.setContent req (transform-aleph-body body (:headers request)))))
    req))

(defn transform-aleph-response
  "Turns a Ring response into something Netty can understand."
  [response options]
  (let [response (wrap-response response)
	rsp (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v-or-vals] (:headers response)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (.addHeader rsp (to-str k) v-or-vals)
	  (doseq [val v-or-vals]
	    (.addHeader rsp (to-str k) val)))))
    (when body
      (.setContent rsp
	(transform-aleph-body body (:headers response))))
    (when-not (channel? body)
      (HttpHeaders/setContentLength rsp (-> rsp .getContent .readableBytes)))
    (when (:keep-alive? options)
      (.setHeader rsp "connection" "keep-alive"))
    rsp))

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

(defn transform-netty-response [^HttpResponse response headers]
  {:status (-> response .getStatus .getCode)
   :headers headers
   :body (transform-netty-body (.getContent response) headers)})
