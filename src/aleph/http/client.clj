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
    [org.jboss.netty.handler.ssl SslHandler]
    [org.jboss.netty.channel
     Channel
     ExceptionEvent]
    [java.nio
     ByteBuffer]
    [java.net
     URI]))


;;;

(defn- create-ssl-handler
  [{:keys [server-name server-port]}]
  (SslHandler.
   (doto (.createSSLEngine (javax.net.ssl.SSLContext/getDefault)
                           server-name
                           server-port)
     (.setUseClientMode true))))

;;;

(defn create-pipeline [client options]
  (let [responses (channel)
        init? (atom false)
        stages [:codec (HttpClientCodec.)
                :inflater (HttpContentDecompressor.)
                :response (message-stage
                           (fn [netty-channel rsp]
                             (enqueue client rsp)
                             nil))]
        pipeline (apply create-netty-pipeline (:name options)
                        (if (= "https" (:scheme options))
                          (concat [:ssl (create-ssl-handler options)]
                                  stages)
                               stages))]
    pipeline))

;;;

(defn- process-options [options]
  (-> options
    split-url
    (update-in [:keep-alive?] #(or % true))))

(defn http-connection
  [options]
  (let [options (process-options options)
	options (merge
		  {:name (str "http-connection:" (:server-name options))}
		  options)
	requests (channel)
	client (create-client
		 #(create-pipeline % options)
		 identity
		 options)]
    (run-pipeline client
      :error-handler (fn [_])
      (fn [ch]
	(let [requests (siphon->> (wrap-request-stream options) ch)]
          (on-closed requests #(close ch))
          (splice
            (wrap-response-stream options ch)
            requests))))))

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
         (run-pipeline response
           :error-handler (fn [ex]
                            (close-connection connection)))
	 (run-pipeline connection
	   :error-handler (fn [ex]
			    (close-connection connection)
			    (error! response ex))
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

