;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.


(ns aleph.http.client
  (:use
    [aleph netty]
    [aleph.core channel pipeline])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpResponseStatus
     HttpClientCodec
     HttpContentDecompressor
     DefaultHttpRequest
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpVersion]
    [org.jboss.netty.channel
     ExceptionEvent]
    [java.net
     URI]))

(def request-methods
  {:get HttpMethod/GET
   :put HttpMethod/PUT
   :delete HttpMethod/DELETE
   :post HttpMethod/POST
   :trace HttpMethod/TRACE
   :connect HttpMethod/CONNECT
   :options HttpMethod/OPTIONS
   :head HttpMethod/HEAD})

(defn transform-request [scheme ^String host ^Integer port request]
  (let [uri (URI. scheme nil host port (:uri request) (:query-string request) (:fragment request))
        req (DefaultHttpRequest.
	      HttpVersion/HTTP_1_1
	      (request-methods (:request-method request))
	      (.toASCIIString uri))]
    (.setHeader req "Host" host)
    (.setHeader req "Accept-Encoding" "gzip")
    (doseq [[^String k v] (:headers request)]
      (.setHeader req k v))
    (when-let [body (:body request)]
      (.setContent req (input-stream->channel-buffer body)))
    req))

(defn transform-response-body [response]
  (update-in response [:body]
    (fn [body]
      (let [content-type (-> response :headers (get "content-type"))
	    charset (-> response :headers (get "charset"))]
	(cond
	  (.startsWith content-type "text") (.toString body (or charset "UTF-8"))
	  :else (channel-buffer->input-stream body))))))

(defn transform-response [^HttpResponse response]
  (transform-response-body
    {:status (-> response .getStatus .getCode)
     :headers (into {}
		(map
		  (fn [[^String k v]] [(.toLowerCase k) v])
		  (into {} (.getHeaders response))))
     :body (.getContent response)}))

;;;

(defn create-pipeline [ch options]
  (let [host (:host options)
	port (:port options)
	scheme (:scheme options)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel response]
		    (enqueue ch (transform-response response))))
      :downstream-error (downstream-stage error-stage-handler))))


