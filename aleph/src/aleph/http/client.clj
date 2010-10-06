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
     DefaultHttpChunk
     HttpContentDecompressor
     DefaultHttpRequest
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpVersion]
    [org.jboss.netty.channel
     Channel
     ExceptionEvent]
    [java.net
     URI]))

;;;

(defn read-streaming-response [headers in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    read-channel
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
    read-channel
    (fn [^HttpResponse response]
      (let [chunked? (.isChunked response)
	    headers (netty-headers response)
	    response (transform-netty-response response headers)]
	(if-not chunked?
	  (enqueue out response)
	  (let [body (:body response)
		stream (channel)
		close (constant-channel)
		response (assoc response :body (splice stream close))]
	    (receive close
	      (fn [_] (.close netty-channel)))
	    (when body
	      (enqueue stream body))
	    (enqueue out response)
	    (read-streaming-response headers in stream)))))
    (fn [_]
      (restart))))

(defn follow-redirect [request-fn response]
  (run-pipeline response
    (fn [response]
      (if (= 301 (:status response))
	(restart (request-fn response))
	response))))

;;;

(defn read-streaming-request [in out headers]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    read-channel
    (fn [chunk]
      (when chunk
	(enqueue out (DefaultHttpChunk. (transform-aleph-body chunk headers))))
      (if (closed? in)
	(enqueue out HttpChunk/LAST_CHUNK)
	(restart)))))

(defn read-requests [in out options]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    read-channel
    (fn [request]
      (enqueue out
	(transform-aleph-request
	  (:scheme options)
	  (:server-name options)
	  (:server-port options)
	  request))
      (when (channel? (:body request))
	(read-streaming-request (:body request) out (:headers request))))
    (fn [_]
      (restart))))

;;;

(defn create-pipeline [client close? options]
  (let [responses (channel)
	init? (atom false)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      ;;:upstream-decoder (upstream-stage (fn [x] (println "client request\n" x) x))
      ;;:downstream-decoder (downstream-stage (fn [x] (println "client response\n" x) x))
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel rsp]
		    (when (compare-and-set! init? false true)
		      (read-responses netty-channel responses client))
		    (enqueue responses rsp)
		    nil))
      :downstream-error (downstream-stage error-stage-handler))))

;;;

(defprotocol HttpClient
  (create-request-channel [c])
  (close-http-client [c]))

(defn raw-http-client
  "Create an HTTP client."
  [options]
  (let [options (-> options
		  split-url
		  (update-in [:server-port] #(or % 80)))
	requests (channel)
	client (create-client
		 #(create-pipeline
		    %
		    (or (:close? options) (constantly false))
		    options)
		 identity
		 options)]
    (run-pipeline client
      #(read-requests requests % options))
    (reify HttpClient
      (create-request-channel [_]
	(run-pipeline client
	  #(splice % requests)))
      (close-http-client [_]
	(enqueue-and-close (-> client run-pipeline wait-for-pipeline)
	  nil)))))

(defn http-request
  ([request]
     (let [client (raw-http-client request)
	   response (http-request client request)]
       response))
  ([client request]
     (run-pipeline client
       create-request-channel
       (fn [ch]
	 (enqueue ch request)
	 (read-channel ch))
       )))

'(fn [response]
  (follow-redirect
    #(http-request
       (-> request
	 (assoc :url (get-in % [:headers "location"]))
	 (dissoc :query-string :uri :server-port :server-name :scheme)))
    response))

;;;

(defn create-websocket-pipeline []
  )
