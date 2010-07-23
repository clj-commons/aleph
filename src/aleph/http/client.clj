;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.


(ns aleph.http.client
  (:use [aleph core pipeline])
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
    ;;(.setHeader req "Accept-Encoding" )
    (doseq [[^String k v] (:headers request)]
      (.setHeader req k v))
    (when-let [body (:body request)]
      (.setContent req (input-stream->channel-buffer body)))))

(defn transform-response [^HttpResponse response]
  {:status (-> response .getStatus .getCode)
   :headers (into {} (.getHeaders response))
   :body (channel-buffer->input-stream (.getContent response))})

;;;

(defn- error-stage-fn [evt]
  (when (instance? ExceptionEvent evt)
    (.printStackTrace ^Throwable (.getCause ^ExceptionEvent evt)))
  evt)

(defn create-pipeline [options]
  (let [host (:host options)
	port (:port options)
	scheme (:scheme options)
	pipeline (create-netty-pipeline
		   :codec (HttpClientCodec.)
		   :inflater (HttpContentDecompressor.)
		   :upstream-error (upstream-stage error-stage-fn)
		   :response (message-stage
			       (fn [ch response]
				 (println (transform-response response))))
		   :downstream-error (downstream-stage error-stage-fn))]
    pipeline))


