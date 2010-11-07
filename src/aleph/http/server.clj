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
    [lamina.core]
    [clojure.pprint])
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
  ([^Channel netty-channel response]
     (respond-with-string netty-channel response "utf-8"))
  ([^Channel netty-channel response charset]
     (let [body (-> response :body (string->byte-buffer charset) byte-buffer->channel-buffer)
	   response (transform-aleph-response
		      (-> response
			(update-in [:headers] assoc "charset" charset)
			(assoc :body body)))]
       (write-to-channel netty-channel response false))))

(defn- respond-with-sequence
  ([netty-channel response]
     (respond-with-sequence netty-channel response "UTF-8"))
  ([netty-channel response charset]
     (respond-with-string netty-channel
       (update-in response [:body] #(apply str %)) charset)))

(defn- respond-with-stream
  [^Channel netty-channel response]
  (let [response (transform-aleph-response (update-in response [:body] #(input-stream->channel-buffer %)))]
    (write-to-channel netty-channel response false)))

(defn- respond-with-file
  [netty-channel response]
  (let [file ^File (:body response)
	content-type (or
		       (URLConnection/guessContentTypeFromName (.getName file))
		       "application/octet-stream")
	fc (.getChannel (RandomAccessFile. file "r"))
	response (-> response
		   (update-in [:headers "content-type"] #(or % content-type))
		   (assoc :body fc))]
    (write-to-channel netty-channel
      (transform-aleph-response response)
      false
      :on-write #(.close fc))))

(defn- respond-with-channel
  [netty-channel response]
  (let [charset (or (get-in response [:headers "charset"]) "UTF-8")
	response (-> response
		   (assoc-in [:headers "charset"] charset))
	initial-response ^HttpResponse (transform-aleph-response response)
	ch (:body response)
	close-channel (channel)
	close-callback (fn [_] (enqueue-and-close close-channel ch))]
    (receive close-channel close-callback)
    (run-pipeline (write-to-channel netty-channel initial-response false)
      (fn [_]
	(receive-in-order ch
	  (fn [msg]
	    (when msg
	      (let [msg (transform-aleph-body msg (:headers response))
		    chunk (DefaultHttpChunk. msg)]
		(write-to-channel netty-channel chunk false)))
	    (when (closed? ch)
	      (cancel-callback close-channel close-callback)
	      (write-to-channel netty-channel HttpChunk/LAST_CHUNK false))))))))

(defn respond [^Channel netty-channel response]
  (let [response (update-in response [:headers]
		   #(merge
		      {"server" "aleph (0.1.2)"}
		      %))
	body (:body response)]
    (cond
      (nil? body) (respond-with-string netty-channel (assoc response :body ""))
      (string? body) (respond-with-string netty-channel response)
      (sequential? body) (respond-with-sequence netty-channel response)
      (channel? body) (respond-with-channel netty-channel response)
      (instance? InputStream body) (respond-with-stream netty-channel response)
      (instance? File body) (respond-with-file netty-channel response))))

;;;

(defn read-streaming-request
  "Read in all the chunks for a streamed request."
  [headers in out]
  (run-pipeline in
    read-channel
    (fn [^HttpChunk request]
      (let [last? (.isLast request)
	    body (transform-netty-body (.getContent request) headers)]
	(if last?
	  (enqueue-and-close out body)
	  (enqueue out body))
	(when-not last?
	  (restart))))))

(defn handle-request
  "Consumes a single request from 'in', and feed the response into 'out'."
  [^HttpRequest netty-request ^Channel netty-channel handler in out]
  (let [chunked? (.isChunked netty-request)
	request (assoc (transform-netty-request netty-request)
		  :scheme :http
		  :remote-addr (->> netty-channel .getRemoteAddress .getAddress .getHostAddress))
	close-channel (channel)
	close-callback (fn [_] (enqueue-and-close out nil))
	cancel-close-callback (fn [_] (cancel-callback close-channel close-callback))]
    (receive close-channel close-callback)
    (receive out cancel-close-callback)
    (if-not chunked?
      (do
	(handler out request)
	nil)
      (let [headers (:headers request)
	    stream (channel)
	    streaming-close-callback (fn [_] (enqueue-and-close stream nil))]
	(receive close-channel streaming-close-callback)
	(handler out (assoc request :body stream))
	(run-pipeline (read-streaming-request headers in stream)
	  (fn [_] (cancel-callback close-channel streaming-close-callback)))))))

(defn non-pipelined-loop
  "Wait for the response for each request before processing the next one."
  [^Channel netty-channel in handler]
  (run-pipeline in
    read-channel
    (fn [^HttpRequest request]
      (let [out (constant-channel)]
	(run-pipeline
	  (handle-request request netty-channel handler in out)
	  (fn [_] out)
	  read-channel
	  (fn [response] (respond netty-channel response))
	  (fn [_] request))))
    (fn [^HttpRequest request]
      (if (HttpHeaders/isKeepAlive request)
	(restart)
	(.close netty-channel)))))

(defn http-session-handler [handler options]
  (let [init? (atom false)
	ch (channel)]
    (message-stage
      (fn [netty-channel request]
	(when (compare-and-set! init? false true)
	  (non-pipelined-loop netty-channel ch handler))
	(enqueue ch request)
	nil))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :decoder (HttpRequestDecoder.)
	  ;;:upstream-decoder (upstream-stage (fn [x] (println "server request\n" x) x))
	  ;;:downstream-decoder (downstream-stage (fn [x] (println "server response\n" x) x))
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  :http-request (http-session-handler handler options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server."
  [handler options]
  (start-server
    #(create-pipeline handler options)
    options))




