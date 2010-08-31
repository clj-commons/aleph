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
    [aleph.http core utils]
    [aleph.core channel pipeline]
    [clojure.contrib.json])
  (:require
    [clj-http.client :as client])
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

;;;

(defn read-streaming-response [headers in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [^HttpChunk response]
      (let [body (transform-netty-body (.getContent response) headers)]
	(if (.isLast response)
	  (enqueue-and-close out body)
	  (do
	    (enqueue out body)
	    (restart)))))))

(defn read-responses [netty-channel in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    receive-from-channel
    (fn [^HttpResponse response]
      (let [chunked? (.isChunked response)
	    headers (netty-headers response)
	    response (transform-netty-response response headers)]
	(if-not chunked?
	  (enqueue out response)
	  (let [body (:body response)
		stream (channel)
		close (single-shot-channel)
		response (assoc response :body (splice stream close))]
	    (receive close
	      (fn [_] (.close netty-channel)))
	    (when body
	      (enqueue stream body))
	    (enqueue out response)
	    (read-streaming-response headers in stream)))))
    (fn [_]
      (restart))))

(defn create-pipeline [out close? options]
  (let [in (channel)
	init? (atom false)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      ;;:upstream-decoder (upstream-stage (fn [x] (println "request" x) x))
      ;;:downstream-decoder (downstream-stage (fn [x] (println "response" x) x))
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel response]
		    (when (compare-and-set! init? false true)
		      (read-responses netty-channel in out))
		    (enqueue in response)))
      :downstream-error (downstream-stage error-stage-handler))))

;;;

(defprotocol HttpClient
  (create-request-channel [c])
  (close-http-client [c]))

(defn raw-http-client
  "Create an HTTP client."
  [options]
  (let [options (-> (split-url options)
		  (update-in [:server-port] #(or % 80)))
	client (create-client
		 #(create-pipeline
		    %
		    (or (:close? options) (constantly false))
		    options)
		 #(transform-aleph-request
		    (:scheme options)
		    (:server-name options)
		    (:server-port options)
		    %)
		 options)]
    (reify HttpClient
      (create-request-channel [_]
	client)
      (close-http-client [_]
	(enqueue-and-close (-> client run-pipeline wait-for-pipeline) nil)))))

(defn http-request
  ([request]
     (http-request
       (raw-http-client request)
       request))
  ([client request]
     (run-pipeline client
       create-request-channel
       (fn [ch]
	 (enqueue ch request)
	 ch))))


