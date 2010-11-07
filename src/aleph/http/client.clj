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
    [aleph.http core utils websocket]
    [lamina.core]
    [clojure.contrib.json])
  (:require
    [clj-http.client :as client])
  (:import
    [org.jboss.netty.handler.codec.http.websocket
     WebSocketFrameEncoder
     WebSocketFrameDecoder]
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpResponseStatus
     HttpClientCodec
     HttpResponseDecoder
     HttpRequestEncoder
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
    [java.nio
     ByteBuffer]
    [java.net
     URI]))

;;;

(defn read-streaming-response [headers in out]
  (run-pipeline in
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

;;;

(defn read-streaming-request [in out headers]
  (run-pipeline in
    read-channel
    (fn [chunk]
      (when chunk
	(enqueue out (DefaultHttpChunk. (transform-aleph-body chunk headers))))
      (if (closed? in)
	(enqueue out HttpChunk/LAST_CHUNK)
	(restart)))))

(defn read-requests [in out options close-fn]
  (run-pipeline in
    read-channel
    (fn [request]
      (when request
	(enqueue out
	  (transform-aleph-request
	    (:scheme options)
	    (:server-name options)
	    (:server-port options)
	    request))
	(when (channel? (:body request))
	  (read-streaming-request (:body request) out (:headers request)))))
    (fn [_]
      (if-not (closed? in)
	(restart)
	(close-fn)))))

;;;

(defn create-pipeline [client options]
  (let [responses (channel)
	init? (atom false)
	pipeline (create-netty-pipeline
		   :codec (HttpClientCodec.)
		   :inflater (HttpContentDecompressor.)
		   ;;:upstream-decoder (upstream-stage (fn [x] (println "client request\n" x) x))
		   ;;:downstream-decoder (downstream-stage (fn [x] (println "client response\n" x) x))
		   :upstream-error (downstream-stage error-stage-handler)
		   :response (message-stage
			       (fn [netty-channel rsp]
				 (when (compare-and-set! init? false true)
				   (read-responses netty-channel responses client))
				 (enqueue responses rsp)
				 nil))
		   :downstream-error (upstream-stage error-stage-handler))]
    pipeline))

;;;

(defn- raw-http-client
  "Create an HTTP client."
  [options]
  (let [options (-> options
		  split-url
		  (update-in [:server-port] #(or % 80))
		  (update-in [:keep-alive?] #(or % true)))
	requests (channel)
	client (create-client
		 #(create-pipeline % options)
		 identity
		 options)]
    (run-pipeline client
      (fn [responses]
	(read-requests
	  requests responses options
	  (fn []
	    (enqueue-and-close requests nil)
	    (enqueue-and-close responses nil)))
	(splice responses requests)))))

(defn http-client
  [options]
  (let [client (raw-http-client options)
	response-channel (channel)
	request-channel (channel)]
    (run-pipeline client
      (fn [client-channel]
	(run-pipeline nil
	  (constantly request-channel)
	  read-channel
	  read-channel
	  #(if %
	     (enqueue client-channel %)
	     (enqueue-and-close client-channel nil))
	  (constantly response-channel)
	  read-channel
	  (read-merge #(read-channel client-channel) #(enqueue %1 %2))
	  (fn [_]
	    (if-not (closed? client-channel)
	      (restart)
	      (enqueue-and-close client-channel nil))))
	(fn []
	  (let [request (constant-channel)
		response (constant-channel)]
	    (dosync
	      (enqueue response-channel response)
	      (enqueue request-channel request))
	    (splice response request)))))))

(defn close-http-client [client]
  (run-pipeline client
    #(enqueue-and-close (%) nil)))

(defn http-request
  ([request]
     (let [client (http-client (assoc request :keep-alive? false))]
       (http-request client request)))
  ([client request]
     ;;TODO: error out on excessive redirects
     (run-pipeline client
       (fn [ch-fn]
	 (let [ch (ch-fn)]
	   (enqueue ch request)
	   (read-channel ch)))
       (fn [response]
	 (if (= 301 (:status response))
	   (http-request
	     (-> request
	       (update-in [:redirect-count] #(if-not % 1 (inc %)))
	       (assoc :url (get-in response [:headers "location"]))
	       (dissoc :query-string :uri :server-port :server-name :scheme)))
	   response)))))

(defn websocket-handshake [protocol]
  {:method :get
   :headers {"Sec-WebSocket-Key1" "18x 6]8vM;54 *(5:  {   U1]8  z [  8" ;;TODO: actually randomly generate these
	     "Sec-WebSocket-Key2" "1_ tx7X d  <  nw  334J702) 7]o}` 0"
	     "Sec-WebSocket-Protocol" protocol
	     "Upgrade" "WebSocket"
	     "Connection" "Upgrade"}
   :body (ByteBuffer/wrap (.getBytes "Tm[K T2u" "utf-8"))})

(def expected-response (ByteBuffer/wrap (.getBytes "fQJ,fN/4F4!~K~MH" "utf-8")))

(defn websocket-pipeline [ch success error]
  (create-netty-pipeline
    :decoder (HttpResponseDecoder.)
    :encoder (HttpRequestEncoder.)
    ;;:upstream-decoder (upstream-stage (fn [x] (println "client response\n" x) x))
    ;;:downstream-decoder (downstream-stage (fn [x] (println "client request\n" x) x))
    :upstream-error (upstream-stage error-stage-handler)
    :response (message-stage
		(fn [netty-channel rsp]
		  (if (not= 101 (-> rsp .getStatus .getCode))
		    (enqueue error [rsp (Exception. "Proper handshake not received.")])
		    (let [pipeline (.getPipeline netty-channel)]
		      (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
		      (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))
		      (.replace pipeline "response" "response"
			(message-stage
			  (fn [netty-channel rsp]
			    (enqueue ch (from-websocket-frame rsp))
			    nil)))
		      (enqueue success ch)))
		  nil))
    :downstream-error (downstream-stage error-stage-handler)))

(defn websocket-client [options]
  (let [options (split-url options)
	result (pipeline-channel)
	client (create-client
		 #(websocket-pipeline % (:success result) (:error result))
		 identity
		 options)]
    (run-pipeline client
      (fn [ch]
	(enqueue ch
	  (transform-aleph-request
	    (:scheme options)
	    (:server-name options)
	    (:server-port options)
	    (websocket-handshake (or (:protocol options) "aleph"))))
	(run-pipeline result
	  (fn [_]
	    (let [in (channel)]
	      (siphon (map* to-websocket-frame in) ch)
	      (splice ch in))))))))
