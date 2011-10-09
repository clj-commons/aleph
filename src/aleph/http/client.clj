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
    [aleph.http.client requests responses]
    [lamina core connections api])
  (:require
    [clj-http.client :as client]
    [aleph.http.websocket.hixie :as hixie])
  (:import
    [java.util.concurrent
     TimeoutException]
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

(defn create-pipeline [client options]
  (let [responses (channel)
	init? (atom false)
	pipeline (create-netty-pipeline (:name options)
		   :codec (HttpClientCodec.)
		   :inflater (HttpContentDecompressor.)
		   :response (message-stage
			       (fn [netty-channel rsp]
				 (enqueue client rsp)
				 nil)))]
    pipeline))

;;;

(defn- process-options [options]
  (-> options
    split-url
    (update-in [:server-port] #(or % 80))
    (update-in [:keep-alive?] #(or % true))))

(defn http-connection
  [options]
  (let [options (process-options options)
	options (merge
		  {:name (str "http-connection:" (:server-name options) ":" (gensym ""))}
		  options)
	requests (channel)
	client (create-client
		 #(create-pipeline % options)
		 identity
		 options)]
    (run-pipeline client
      :error-handler (fn [_])
      (fn [ch]
	(splice
	  (wrap-response-stream options ch)
	  (siphon->> (wrap-request-stream options) ch))))))

(defn- http-client- [client-fn options]
  (let [options (process-options options)
	options (merge
		  {:name
		   (str "http-client:" (:server-name options) ":" (gensym ""))
		   :description
		   (str (:scheme options) "://" (:server-name options) ":" (:server-port options))}
		  options)
	client (client-fn
		 #(http-connection options)
		 options)
	f (fn [request timeout]
	    (if (map? request)
	      (client
		(assoc (merge options request)
		  :keep-alive? true)
		timeout)
	      (client request timeout)))]
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
   response is received, the result will emit an error."
  ([request]
     (http-request request -1))
  ([request timeout]
     (let [start (System/currentTimeMillis)
	   elapsed #(- (System/currentTimeMillis) start)
	   latch (atom false)
	   request (merge {:keep-alive? false} request)
	   response (result-channel)]

       ;; timeout
       (when-not (neg? timeout)
	 (run-pipeline nil
           (wait-stage (- timeout (elapsed)))
	   (fn [_]
	     (when (compare-and-set! latch false true)
	       (error! response
		 (TimeoutException. (str "HTTP request timed out after " (elapsed) " milliseconds.")))))))

       ;; request
       (let [connection (http-connection
			  (update-in request [:probes :errors]
			    #(or % nil-channel)))
	     close-connection (pipeline :error-handler (fn [_]) close)]
	 (run-pipeline connection
	   :error-handler (fn [ex]
			    (close-connection connection)
			    (when-not (instance? TimeoutException)
			      (error! response ex)))
	   (fn [ch]
	     (enqueue ch request)
	     (read-channel ch timeout))
	   (fn [rsp]
	     (if (compare-and-set! latch false true)
	       (do
		 (if (channel? (:body rsp))
		   (on-closed (:body rsp)
		     #(close-connection connection))
		   (close-connection connection))
		 (success! response rsp))
	       (close-connection connection)))))
       
       response)))

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

(defn websocket-pipeline [ch result options]
  (create-netty-pipeline (:name options)
    :decoder (HttpResponseDecoder.)
    :encoder (HttpRequestEncoder.)
    :handshake (message-stage
		(fn [^Channel netty-channel ^HttpResponse rsp]
		  (if (not= 101 (-> rsp .getStatus .getCode))
		    (error! result (Exception. "Proper handshake not received."))
		    (let [pipeline (.getPipeline netty-channel)]
		      (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
		      (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))
		      (.replace pipeline "handshake" "response"
                        (message-stage
			  (fn [netty-channel rsp]
			    (enqueue ch (hixie/from-websocket-frame rsp))
			    nil)))
		      (success! result ch)))
		  nil))))

(defn websocket-client [options]
  (let [options (split-url options)
	options (merge
		  {:name (str "websocket-client:" (:host options) ":" (gensym ""))}
		  options)
	result (result-channel)
	client (create-client
		 #(websocket-pipeline % result options)
		 identity
		 options)]
    (run-pipeline client
      (fn [ch]
	(enqueue ch
	  (transform-aleph-request
	    (websocket-handshake (or (:protocol options) "aleph"))
	    (:scheme options)
	    (:server-name options)
	    (:server-port options)
	    options))
	(run-pipeline result
	  (fn [_]
	    (let [in (channel)]
	      (siphon (map* hixie/to-websocket-frame in) ch)
	      (on-closed in #(close ch))
	      (splice ch in))))))))
