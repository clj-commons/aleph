;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.client
  (:use
    [aleph netty formats]
    [aleph.http utils]
    [aleph.core channel pipeline]
    [clojure.contrib.json])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpResponseStatus
     HttpClientCodec
     HttpChunk
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
  (let [request (wrap-request request)
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

(defn transform-response-body [response]
  (update-in response [:body]
    (fn [body]
      (let [content-type (or (-> response :headers (get "content-type")) "text/plain")
	    charset (-> response :headers (get "charset"))]
	(cond
	  (.startsWith content-type "text") (.toString body (or charset "UTF-8"))
	  (= content-type "application/json") (let [s (.toString body (or charset "UTF-8"))]
						(when-not (empty? s)
						  (read-json s true false nil)))
	  :else (channel-buffer->input-stream body))))))

(defn transform-headers [^HttpResponse response]
  (into {}
    (map
      (fn [[^String k v]] [(.toLowerCase k) v])
      (into {} (.getHeaders response)))))

(defn transform-chunk [^HttpChunk chunk headers]
  (transform-response-body
    {:headers headers
     :body (.getContent chunk)}))

(defn transform-response [^HttpResponse response headers]
  (transform-response-body
    {:status (-> response .getStatus .getCode)
     :headers headers
     :body (.getContent response)}))

;;;

(defn last? [response]
  (or
    (and (instance? HttpChunk response) (.isLast ^HttpChunk response))
    (and (instance? HttpResponse response) (not (.isChunked ^HttpResponse response)))))

(defn create-pipeline [ch close? options]
  (let [headers (atom nil)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel response]
		    (when-not @headers
		      (reset! headers (transform-headers response)))
		    (let [response (if (instance? HttpResponse response)
				     (transform-response response @headers)
				     (transform-chunk response @headers))]
		      (enqueue ch response)
		      (when (close? response)
			(.close netty-channel))
		      (when (last? response)
			(reset! headers nil)))))
      :downstream-error (downstream-stage error-stage-handler))))

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
	(enqueue-and-close client nil)))))

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


