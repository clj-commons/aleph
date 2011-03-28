;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.http.server
  (:use
    [aleph netty formats]
    [aleph.http utils core websocket]
    [lamina core]
    [lamina.core.pipeline :only (success-result)]
    [clojure.pprint])
  (:require
    [clojure.contrib.logging :as log])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpHeaders$Names
     HttpChunk
     DefaultHttpChunk
     DefaultHttpResponse
     HttpVersion
     HttpResponseStatus
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel
     Channels
     ChannelPipeline
     ChannelFuture
     MessageEvent
     ExceptionEvent
     Channels
     DefaultFileRegion]
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBufferInputStream
     ChannelBuffers]
    [java.io
     ByteArrayInputStream
     InputStream
     File
     RandomAccessFile]
    [java.net
     URLConnection]))

;;;

(defn- respond-with-string
  ([^Channel netty-channel options response]
     (respond-with-string netty-channel options response "utf-8"))
  ([^Channel netty-channel options response charset]
     (let [body (-> response :body (string->byte-buffer charset) byte-buffer->channel-buffer)
	   response (transform-aleph-response
		      (-> response
			(update-in [:headers] assoc "Charset" charset)
			(assoc :body body))
		      options)]
       (write-to-channel netty-channel response false))))

(defn- respond-with-sequence
  ([netty-channel options response]
     (respond-with-sequence netty-channel options response "UTF-8"))
  ([netty-channel options response charset]
     (respond-with-string netty-channel options
       (update-in response [:body] #(apply str %)) charset)))

(defn- respond-with-stream
  [^Channel netty-channel options response]
  (let [stream ^InputStream (:body response)
	response (transform-aleph-response
		   (update-in response [:body] #(input-stream->channel-buffer %))
		   options)]
    (run-pipeline
      (write-to-channel netty-channel response false)
      (fn [_] (.close stream)))))

(defn- respond-with-file
  [netty-channel options response]
  (let [file ^File (:body response)
	content-type (or
		       (URLConnection/guessContentTypeFromName (.getName file))
		       "application/octet-stream")
	fc (.getChannel (RandomAccessFile. file "r"))
	response (-> response
		   (update-in [:headers "Content-Type"] #(or % content-type))
		   (assoc :body fc))]
    (write-to-channel netty-channel
      (transform-aleph-response response options)
      false
      :on-write #(.close fc))))

(defn- respond-with-channel
  [netty-channel options returned-result response]
  (let [charset (get-in response [:headers "Charset"] "utf-8")
	response (assoc-in response [:headers "Charset"] charset)
	initial-response ^HttpResponse (transform-aleph-response response options)
	ch (:body response)
	headers (:headers response)
	write-to-channel (fn [& args]
			   (let [result (apply write-to-channel args)]
			     (enqueue returned-result result)
			     result))]
    (run-pipeline (write-to-channel netty-channel initial-response false)
      (fn [_]
	(receive-in-order ch
	  (fn [msg]
	    (when msg
	      (let [msg (to-channel-buffer
			  (:body
			    (encode-aleph-msg
			      {:headers headers :body msg}
			      options))
			  charset)
		    chunk (DefaultHttpChunk. msg)]
		(write-to-channel netty-channel chunk false)
		nil)))))
      (fn [_]
	(write-to-channel netty-channel HttpChunk/LAST_CHUNK false)))))

(defn respond-with-channel-buffer
  [netty-channel options response]
  (let [response (update-in response [:headers "Content-Type"]
		   #(or % "application/octet-stream"))]
    (write-to-channel netty-channel
      (transform-aleph-response response options)
      false)))

(defn respond [^Channel netty-channel options returned-result response]
  (let [response (update-in response [:headers]
		   #(merge
		      {"Server" "aleph (0.1.5)"}
		      %))
	body (:body response)]
    (cond
      (nil? body) (respond-with-string netty-channel options (assoc response :body ""))
      (string? body) (respond-with-string netty-channel options response)
      (channel? body) (respond-with-channel netty-channel options returned-result response)
      (instance? InputStream body) (respond-with-stream netty-channel options response)
      (instance? File body) (respond-with-file netty-channel options response)
      :else (let [response (encode-aleph-msg response options)
		  original-body body
		  body (:body response)]
	      (cond
		(sequential? body)
		(respond-with-sequence netty-channel options response)

		(to-channel-buffer? body)
		(respond-with-channel-buffer netty-channel options (update-in response [:body] to-channel-buffer))

		:else
		(throw (Exception. (str "Don't know how to respond with body of type " (prn-str original-body) (class body)))))))))

;;;

(defn wrap-response-channel [ch]
  (proxy-channel
    (fn [[rsp]]
      (if (channel? (:body rsp))
	(let [result (channel)]
	  [result [[result rsp]]])
	(let [result (result-channel)]
	  [result [[result rsp]]])))
    ch))

(defn siphon-result* [src dst]
  (when (result-channel? dst)
    (siphon-result src dst)))

(defn read-streaming-request
  "Read in all the chunks for a streamed request."
  [headers options in out]
  (run-pipeline in
    read-channel
    (fn [^HttpChunk request]
      (let [last? (.isLast request)
	    body (:body (decode-aleph-msg {:headers headers :body (.getContent request)} options))]
	(if last?
	  (close out)
	  (enqueue out body))
	(when-not last?
	  (restart))))))

(defn handle-request
  "Consumes a single request from 'in', and feed the response into 'out'."
  [^Channel netty-channel options ^HttpRequest netty-request handler in out]
  (let [chunked? (.isChunked netty-request)
	request (assoc (transform-netty-request netty-request options)
		  :scheme :http
		  :remote-addr (channel-origin netty-channel))]
    (if-not chunked?
      (do
	(handler out request)
	nil)
      (let [headers (:headers request)
	    stream (channel)]
	(handler out (assoc request :body stream))
	(read-streaming-request headers options in stream)))))

(defn non-pipelined-loop
  "Wait for the response for each request before processing the next one."
  [^Channel netty-channel options in handler]
  (run-pipeline in
    :error-handler (fn [ex]
		     (log/error "Error in handler, closing connection." ex)
		     (.close netty-channel))
    read-channel
    (fn [^HttpRequest request]
      (let [out (wrap-response-channel (constant-channel))]
	(run-pipeline
	  (handle-request netty-channel options request handler in out)
	  (fn [_] (read-channel out))
	  (fn [[returned-result response]]
	    (siphon-result*
	      (respond netty-channel options returned-result
		(pre-process-aleph-message
		  (assoc response :keep-alive? (HttpHeaders/isKeepAlive request))
		  options))
	      returned-result))
	  (constantly request))))
    (fn [^HttpRequest request]
      (if (HttpHeaders/isKeepAlive request)
	(restart)
	(close-channel netty-channel)))))

(defn simple-request-handler
  [netty-channel options request handler]
  (let [out (wrap-response-channel (constant-channel))]
    (handle-request netty-channel options request handler nil out)
    (receive out
      (fn [[returned-result response]]
	(siphon-result*
	  (run-pipeline
	    (respond netty-channel options returned-result
	      (pre-process-aleph-message
		(assoc response :keep-alive? false)
		options))
	    (fn [_]
	      (close-channel netty-channel)))
	  returned-result)))))

(defn http-session-handler [handler options]
  (let [init? (atom false)
 	ch (channel)]
    (message-stage
      (fn [^Channel netty-channel ^HttpRequest request]
	(try
	  (if (not (or @init? (.isChunked request) (HttpHeaders/isKeepAlive request)))
	    (simple-request-handler netty-channel options request handler)
	    (do
	      (when (compare-and-set! init? false true)
		(non-pipelined-loop netty-channel options ch handler))
	      (enqueue ch request)))
	  (catch Exception ex
	    (log/error "Error in handler, closing connection." ex)
	    (.close netty-channel)))
	nil))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :decoder (HttpRequestDecoder.)
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  :http-request (http-session-handler handler options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server on the specified :port.  To support WebSockets, set :websocket to
   true.

   'handler' should be a function that takes two parameters, a channel and a request hash.
   The request is a hash that conforms to the Ring standard, with :websocket set to true
   if it is a WebSocket handshake.  If the request is chunked, the :body will also be a
   channel.

   If the request is a standard HTTP request, the channel will accept a single message, which
   is the response.  For a chunked response, the response :body should be a channel.  If the
   request is a WebSocket handshake, the channel represents a full duplex socket, which
   communicates via complete (i.e. non-streaming) strings."
  [handler options]
  (start-server
    #(create-pipeline handler options)
    options))




