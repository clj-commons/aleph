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
    [aleph.core]
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
    [org.jboss.netty.handler.codec.http.websocket
     WebSocketFrame
     WebSocketFrameDecoder
     WebSocketFrameEncoder]
    [org.jboss.netty.channel
     Channel
     Channels
     ChannelPipeline
     ChannelUpstreamHandler
     ChannelFuture
     MessageEvent
     ExceptionEvent
     ChannelFutureListener
     ChannelFutureProgressListener
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
     FileInputStream]
    [java.net
     URLConnection]))

;;;

(defn- respond-with-string
  ([^Channel netty-channel response options]
     (respond-with-string netty-channel response options "UTF-8"))
  ([^Channel netty-channel response options charset]
     (let [body (-> response :body (string->byte-buffer charset) byte-buffer->channel-buffer)
	   response (transform-aleph-response
		      (-> response
			(update-in [:headers] assoc "charset" charset)
			(assoc :body body))
		      options)]
       (-> netty-channel
	 (.write response)
	 (.addListener (response-listener options))))))

(defn- respond-with-sequence
  ([netty-channel response options]
     (respond-with-sequence netty-channel response options "UTF-8"))
  ([netty-channel response options charset]
     (respond-with-string netty-channel
       (update-in response [:body] #(apply str %)) options charset)))

(defn- respond-with-stream
  [^Channel netty-channel response options]
  (let [response (transform-aleph-response
		   (update-in response [:body] #(input-stream->channel-buffer %))
		   options)]
    (-> netty-channel
      (.write response)
      (.addListener (response-listener options)))))

;;TODO: use a more efficient file serving mechanism
(defn- respond-with-file
  [netty-channel response options]
  (let [file ^File (:body response)
	content-type (or
		       (URLConnection/guessContentTypeFromName (.getName file))
		       "application/octet-stream")]
    (respond-with-stream
      netty-channel
      (-> response
	(update-in [:headers "content-type"] #(or % content-type))
	(update-in [:body] #(FileInputStream. ^File %)))
      options)))

(defn- respond-with-channel
  [netty-channel response options]
  (let [charset (or (get-in response [:headers "charset"]) "UTF-8")
	response (-> response
		   (assoc-in [:headers "charset"] charset)
		   (assoc-in [:headers "transfer-encoding"] "chunked"))
	initial-response ^HttpResponse (transform-aleph-response (dissoc response :body) options)
	keep-alive? (:keep-alive? options)
	ch (:body response)
	close-channel (:close-channel options)
	close-callback (fn [_] (enqueue-and-close close-channel ch))]
    (receive close-channel close-callback)
    (-> netty-channel
      (.write initial-response)
      (.addListener (response-listener (assoc options :close? false))))
    (receive-in-order ch
      (fn [msg]
	(when msg
	  (let [msg (transform-aleph-body msg (:headers response))
		chunk (DefaultHttpChunk. msg)]
	    (-> netty-channel
	      (.write chunk)
	      (.addListener (response-listener (assoc options :close? false))))
	    (when (closed? ch)
	      (cancel-callback close-channel close-callback)
	      (-> netty-channel
		(.write HttpChunk/LAST_CHUNK)
		(.addListener (response-listener (assoc options :close? (not keep-alive?))))))))))))

(defn respond [netty-channel response options]
  (try
    (let [response (update-in response [:headers]
		     #(merge
			{"server" "Aleph (0.1.1)"}
			%))
	  body (:body response)]
      (cond
	(nil? body) (respond-with-string netty-channel (assoc response :body "") options)
	(string? body) (respond-with-string netty-channel response options)
	(sequential? body) (respond-with-sequence netty-channel response options)
	(channel? body) (respond-with-channel netty-channel response options)
	(instance? InputStream body) (respond-with-stream netty-channel response options)
	(instance? File body) (respond-with-file netty-channel response options)))
    (catch Exception e
      (.printStackTrace e))))

;;;

(defn- respond-to-handshake [ctx ^HttpRequest request]
  (let [pipeline (-> ctx .getChannel .getPipeline)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (-> ctx .getChannel (.write (websocket-response request)))
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))))

(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]

	(if-let [msg (message-event evt)]

	  (cond
	    (instance? WebSocketFrame msg)
	    (enqueue outer (from-websocket-frame msg))

	    (instance? HttpRequest msg)
	    (if (websocket-handshake? msg)
	      (let [ch (.getChannel ctx)]
		(receive-all outer
		  (fn [msg]
		    (when msg
		      (.write ch (to-websocket-frame msg)))
		    (when (closed? outer)
		      (.close ch))))
		(respond-to-handshake ctx msg)
		(handler inner (assoc (transform-netty-request msg) :websocket true)))
	      (.sendUpstream ctx evt)))
	  
	  (if-let [ch (channel-event evt)]
	    (when-not (.isConnected ch)
	      (enqueue-and-close inner nil)
	      (enqueue-and-close outer nil))
	    (.sendUpstream ctx evt)))))))

;;;

(defn read-streaming-request [headers in out]
  (run-pipeline in
    :error-handler (fn [_ ex] (.printStackTrace ex))
    read-channel
    (fn [^HttpChunk request]
      (let [last? (.isLast request)
	    body (transform-netty-body (.getContent request) headers)]
	(if last?
	  (enqueue-and-close out body)
	  (enqueue out body))
	(when-not last?
	  (restart))))))

(defn read-requests [in netty-channel handler options]
  (let [remote-addr (->> netty-channel .getRemoteAddress .getAddress .getHostAddress)
	close-channel (:close-channel options)
	cancel-close-callback #(cancel-callback close-channel %)]
    (run-pipeline in
      :error-handler (fn [_ ex] (.printStackTrace ex))
      read-channel
      (fn [^HttpRequest netty-request]
	(let [chunked? (.isChunked netty-request)
	      keep-alive? (.isKeepAlive netty-request)
	      request (assoc (transform-netty-request netty-request)
			:scheme :http
			:remote-addr remote-addr)
	      out (constant-channel)
	      close-callback (fn [_]
			       (when-not (sealed? out)
				 (enqueue-and-close out nil)))]
	  ;; handle connection closing
	  (receive close-channel close-callback)	  
	  ;; response handling
	  (receive out
	    #(io!
	       (respond
		 netty-channel
		 %
		 (assoc options
		   :keep-alive? keep-alive?
		   :close? (not (or keep-alive? (channel? (:body %))))))))
	  ;; request handling
	  (if-not chunked?
	    (do
	      (handler out request)
	      close-callback)
	    (let [headers (:headers request)
		  stream (channel)
		  streaming-close-callback (fn [_]
					     (when-not (sealed? stream)
					       (enqueue-and-close stream nil)))]
	      (receive close-channel streaming-close-callback)
	      (when (pos? (.readableBytes (.getContent netty-request)))
		(enqueue stream (transform-netty-body (.getContent netty-request) headers)))
	      (handler out (assoc request :body stream))
	      (run-pipeline (read-streaming-request headers in stream)
		(fn [_]
		  (cancel-close-callback streaming-close-callback)
		  close-callback))))))
      (fn [callback]
	(cancel-close-callback callback)
	(restart)))))

(defn http-session-handler [handler close-channel options]
  (let [init? (atom false)
	ch (channel)]
    (message-stage
      (fn [netty-channel request]
	(when (compare-and-set! init? false true)
	  (read-requests ch netty-channel handler options))
	(enqueue ch request)
	nil))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [close-channel (constant-channel)
	options (assoc options :close-channel close-channel)
	pipeline ^ChannelPipeline
	(create-netty-pipeline
	  :decoder (HttpRequestDecoder.)
	  ;;:upstream-decoder (upstream-stage (fn [x] (println "server request\n" x) x))
	  ;;:downstream-decoder (downstream-stage (fn [x] (println "server response\n" x) x))
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :upstream-error (upstream-stage error-stage-handler)
	  ;;:close (channel-close-stage (fn [_] (enqueue close-channel nil)))
	  :http-request (http-session-handler handler close-channel options)
	  :downstream-error (downstream-stage error-stage-handler))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server."
  [handler options]
  (start-server
    #(create-pipeline handler options)
    (assoc options
      :error-handler (fn [^Throwable e] (.printStackTrace e)))))




