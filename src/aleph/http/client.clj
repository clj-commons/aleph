;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.http.client
  (:use
    [aleph netty formats]
    [aleph.http core utils websocket]
    [lamina core connections])
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

(defn read-streaming-response [response options in out]
  (run-pipeline in
    read-channel
    (fn [^HttpChunk chunk]
      (let [body (:body (decode-aleph-msg (assoc response :body (.getContent chunk)) options))]
	(if (.isLast chunk)
	  (close out)
	  (do
	    (enqueue out body)
	    (restart)))))))

(defn read-responses [netty-channel options in out]
  (run-pipeline in
    read-channel
    (fn [^HttpResponse response]
      (let [chunked? (.isChunked response)
	    headers (netty-headers response)
	    response (transform-netty-response response headers options)]
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
	    (read-streaming-response response options in stream)))))
    (fn [_]
      (restart))))

;;;

(defn read-streaming-request [request options in out]
  (run-pipeline in
    read-channel
    (fn [chunk]
      (when chunk
	(enqueue out
	  (DefaultHttpChunk. (:body (encode-aleph-msg (assoc request :body chunk) options)))))
      (if (drained? in)
	(enqueue out HttpChunk/LAST_CHUNK)
	(restart)))))

(defn read-requests [in out options close-fn]
  (run-pipeline in
    read-channel
    (fn [request]
      (let [request (wrap-client-request request)]
	(when request
	  (enqueue out
	    (transform-aleph-request
	      (:scheme options)
	      (:server-name options)
	      (:server-port options)
	      (pre-process-aleph-message request options)
	      options))
	  (when (channel? (:body request))
	    (read-streaming-request request options (:body request) out)))))
    (fn [_]
      (if-not (drained? in)
	(restart)
	(close-fn)))))

;;;

(defn create-pipeline [client options]
  (let [responses (channel)
	init? (atom false)
	pipeline (create-netty-pipeline
		   :codec (HttpClientCodec.)
		   :inflater (HttpContentDecompressor.)
		   :upstream-error (downstream-stage error-stage-handler)
		   :response (message-stage
			       (fn [netty-channel rsp]
				 (when (compare-and-set! init? false true)
				   (read-responses netty-channel options responses client))
				 (enqueue responses rsp)
				 nil))
		   :downstream-error (upstream-stage error-stage-handler))]
    pipeline))

;;;

(defn- process-options [options]
  (-> options
    split-url
    (update-in [:server-port] #(or % 80))
    (update-in [:keep-alive?] #(or % true))))

(defn- http-connection
  [options]
  (let [options (process-options options)
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
	    (close requests)
	    (close responses)))
	(splice responses requests)))))

(defn- http-client- [client-fn options]
  (let [options (process-options options)
	client (client-fn
		 #(http-connection options)
		 (str (:scheme options) "://" (:server-name options) ":" (:server-port options)))
	f (fn [request timeout]
	    (if (map? request)
	      (client (assoc (merge options request)
			:keep-alive? true))
	      (client request)))]
    (fn this
      ([request]
	 (this request -1))
      ([request timeout]
	 (f request timeout)))))

(defn http-client
  "Returns a function which represents a persistent connection to the server specified by
   :url.  The function will take an HTTP request per the Ring spec and optionally a timeout,
   and returns a result-channel that will emit the HTTP response.  Redirects will not be
   followed.

   The connection can be closed using lamina.connections/close-connection.

   Requests will only be sent to the server once the response to the previous request has
   been received.  To make concurrent requests, open multiple clients."
  [options]
  (http-client- client options))

(defn pipelined-http-client
  "Returns a function which represents a persistent connection to the server specified by
   :url.  The function will take an HTTP request per the Ring spec and optionally a timeout,
   and returns a result-channel that will emit the HTTP response.  Redirects will not be
   followed.

   The connection can be closed using lamina.connections/close-connection.

   Requests will be sent to the server as soon as they are made, under the assumption that
   responses will be sent in the same order.  This is not always a safe assumption (see
   http://en.wikipedia.org/wiki/HTTP_pipelining ), use only where you're sure the underlying
   assumptions holds."
  [options]
  (http-client- pipelined-client options))

(defn http-request
  "Takes an HTTP request structured per the Ring spec, and returns a result-channel
   that will emit an HTTP response.  If a timeout is specified and elapses before a
   response is received, the result will emit an error.

   Redirects will automatically be followed.  If a timeout is specified, each redirect
   will be allowed the full timeout."
  ([request]
     (http-request request -1))
  ([request timeout]
     (let [connection (http-connection request)
	   latch (atom false)
	   request (assoc request :keep-alive? false)]

       ;; timeout
       (when (pos? timeout)
	 (run-pipeline (timed-channel timeout)
	   read-channel
	   (fn [_]
	     (when (compare-and-set! latch false true)
	       (run-pipeline connection
		 :error-handler (fn [_])
		 #(close %))))))

       ;; request
       (run-pipeline connection
	 (fn [ch]
	   (enqueue ch request)
	   (read-channel ch))
	 (fn [response]
	   (reset! latch true)
	   (if (= 301 (:status response))
	     (http-request
	       (-> request
		 (update-in [:redirect-count] #(if-not % 1 (inc %)))
		 (assoc :url (get-in response [:headers "location"]))
		 (dissoc :query-string :uri :server-port :server-name :scheme))
	       timeout)
	     response))))))

;;;

(defn websocket-handshake [protocol]
  (wrap-client-request
    {:method :get
     :headers {"Sec-WebSocket-Key1" "18x 6]8vM;54 *(5:  {   U1]8  z [  8" ;;TODO: actually randomly generate these
	       "Sec-WebSocket-Key2" "1_ tx7X d  <  nw  334J702) 7]o}` 0"
	       "Sec-WebSocket-Protocol" protocol
	       "Upgrade" "WebSocket"
	       "Connection" "Upgrade"}
     :body (ByteBuffer/wrap (.getBytes "Tm[K T2u" "utf-8"))}))

(def expected-response (ByteBuffer/wrap (.getBytes "fQJ,fN/4F4!~K~MH" "utf-8")))

(defn websocket-pipeline [ch success error]
  (create-netty-pipeline
    :decoder (HttpResponseDecoder.)
    :encoder (HttpRequestEncoder.)
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
	result (result-channel)
	client (create-client
		 #(websocket-pipeline % (.success result) (.error result))
		 identity
		 options)]
    (run-pipeline client
      (fn [ch]
	(enqueue ch
	  (transform-aleph-request
	    (:scheme options)
	    (:server-name options)
	    (:server-port options)
	    (websocket-handshake (or (:protocol options) "aleph"))
	    options))
	(run-pipeline result
	  (fn [_]
	    (let [in (channel)]
	      (siphon (map* to-websocket-frame in) ch)
	      (splice ch in))))))))
