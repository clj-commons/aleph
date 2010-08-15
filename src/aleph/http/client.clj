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
    [aleph.http utils]
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
	request (if (:cookie request)
		  (assoc-in request [:headers "cookie"] (-> request :cookie hash->cookie))
		  request)
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

(defn transform-response-body [response]
  (update-in response [:body]
    (fn [body]
      (let [content-type (or (-> response :headers (get "content-type")) "text/plain")
	    charset (-> response :headers (get "charset"))]
	(cond
	  (.startsWith content-type "text") (.toString body (or charset "UTF-8"))
	  :else (channel-buffer->input-stream body))))))

(defn transform-response [^HttpResponse response]
  (let [headers (into {}
		  (map
		    (fn [[^String k v]] [(.toLowerCase k) v])
		    (into {} (.getHeaders response))))]
    (transform-response-body
      {:status (-> response .getStatus .getCode)
       :headers headers
       :body (.getContent response)})))

;;;

(defn create-pipeline [ch close? options]
  (create-netty-pipeline
    :codec (HttpClientCodec.)
    :inflater (HttpContentDecompressor.)
    :upstream-error (upstream-stage error-stage-handler)
    :response (message-stage
		(fn [netty-channel response]
		  (let [response (transform-response response)]
		    (enqueue ch response)
		    (when (close? response)
		      (.close netty-channel)))))
    :downstream-error (downstream-stage error-stage-handler)))

;;;

(defprotocol HttpClient
  (create-request-channel [c])
  (close-http-client [c]))

(defn split-url [request]
  (if-not (:url request)
    request
    (let [uri (URI. (:url request))
	  port (.getPort uri)]
      (->
	request
	(assoc
	  :server-name (.getHost uri)
	  :server-port (if (neg? port) 80 port)
	  :scheme (.getScheme uri)
	  :uri (.getPath uri)
	  :query-string (.getQuery uri)
	  :fragment (.getFragment uri))
	(dissoc :url)))))

(defn raw-http-client
  "Create an HTTP client."
  [options]
  (let [client (create-client
		 #(create-pipeline
		    %
		    (or (:close? options) (constantly false))
		    options)
		 #(transform-request
		    (:scheme options)
		    (:server-name options)
		    (:server-port options)
		    %)
		 options)]
    (reify HttpClient
      (create-request-channel [_]
	client)
      (close-http-client [_]
	(enqueue client nil)))))

(defn http-request
  ([request]
     (let [request (split-url request)]
       (http-request
	 (raw-http-client request)
	 request)))
  ([client request]
     (run-pipeline
       client
       create-request-channel
       (fn [ch]
	 (enqueue ch (split-url request))
	 ch))))


