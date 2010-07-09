;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http
  (:use
    [aleph.core]
    [clojure.pprint])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
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
     ChannelPipeline
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
    [org.jboss.netty.handler.stream
     ChunkedFile
     ChunkedWriteHandler]
    [java.io
     ByteArrayInputStream
     InputStream
     File
     FileInputStream
     RandomAccessFile]
    [java.net
     URLConnection]))

(defn request-method
  "Get HTTP method from Netty request."
  [^HttpRequest msg]
  (->> msg .getMethod .getName .toLowerCase keyword))

(defn request-headers
  "Get headers from Netty request."
  [^HttpMessage req]
  {:headers (into {} (.getHeaders req))
   :chunked? (.isChunked req)
   :keep-alive? (HttpHeaders/isKeepAlive req)
   :last-chunk (and (.isChunked req) (.isLast ^HttpChunk req))})

(defn request-body
  "Get body from Netty request."
  [^HttpMessage req]
  (let [content-length (HttpHeaders/getContentLength req)
	has-content? (pos? content-length)]
    (when has-content?
      {:content-length content-length
       :body (channel-buffer->input-stream (.getContent req))
       })))

(defn request-uri
  "Get URI from Netty request."
  [^HttpMessage req]
  (let [uri (.getUri req)]
    {:uri uri
     :query-string (when (.contains uri "?")
		     (last (.split uri "?")))}))

(defn transform-request
  "Transforms a Netty request into a Ring request."
  [^HttpRequest req]
  (merge
    (request-body req)
    (request-headers req)
    (request-uri req)))

;;;

(defn call-error-handler
  "Calls the error-handling function."
  [options req e]
  ((:error-handler options) req e))

(defn response-listener
  "Handles the completion of the response."
  [options request]
  (let [channel ^Channel (:channel request)]
    (reify ChannelFutureListener
      (operationComplete [_ future]
	(if (.isSuccess future)
	  (when-not (:keep-alive? request)
	    (.close channel))
	  (call-error-handler options request (.getCause future)))))))

(defn transform-response
  "Turns a Ring response into something Netty can understand."
  [request response]
  (let [msg (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v] (:headers response)]
      (.addHeader msg k v))
    (when body
      (.setContent msg body))
    (when (:keep-alive? request)
      (HttpHeaders/setContentLength msg (-> msg .getContent .readableBytes)))
    (when (:chunked? request)
      (.setChunked msg true))
    msg))

(defn respond-with-string
  ([request response options]
     (respond-with-string request response options "UTF-8"))
  ([request response options charset]
     (let [channel ^Channel (:channel request)
	   body (ChannelBuffers/copiedBuffer (:body response) charset)
	   response (transform-response request (assoc response :body body))]
       (-> channel
	 (.write response)
	 (.addListener (response-listener options request))))))

(defn respond-with-sequence
  ([request response options]
     (respond-with-sequence request response options "UTF-8"))
  ([request response options charset]
     (respond-with-string request (update-in response [:body] #(apply str %)) options charset)))

(defn respond-with-stream
  [request response options]
  (let [channel ^Channel (:channel request)
	response (transform-response request (update-in response [:body] #(input-stream->channel-buffer %)))]
    (-> channel
      (.write response)
      (.addListener (response-listener options request)))))

;;TODO: use the more efficient file serving mechanisms
(defn respond-with-file
  [request response options]
  (let [file ^File (:body response)
	content-type (or (URLConnection/guessContentTypeFromName (.getName file)) "application/octet-stream")]
    (respond-with-stream
      request
      (-> response
	(update-in [:headers "Content-Type"] #(or % content-type))
	(update-in [:body] #(FileInputStream. %)))
      options)))

;;;

(defn- error-stage-fn [evt]
  (when (instance? ExceptionEvent evt)
    (.printStackTrace (.getCause evt)))
  evt)

(defn request-handler
  "Creates a pipeline stage that handles a Netty request."
  [handler options]
  (upstream-stage
    (fn [evt]
      (when-let [request ^HttpRequest (event-message evt)]
	(handler
	  (merge
	    (transform-request request)
	    {:channel (.getChannel ^MessageEvent evt)
	     :respond (fn [this response]
			(let [body (:body response)]
			  (cond
			    (string? body) (respond-with-string this response options)
			    (sequential? body) (respond-with-sequence this response options)
			    (instance? InputStream body) (respond-with-stream this response options)
			    (instance? File body) (respond-with-file this response options))))}))))))

(defn http-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (create-pipeline
    :decoder (HttpRequestDecoder.)
    :encoder (HttpResponseEncoder.)
    :deflater (HttpContentCompressor.)
    :file-handler (ChunkedWriteHandler.)
    :downstream-error (downstream-stage error-stage-fn)
    :request (request-handler
	       (fn [req]
		 (let [req (assoc req
			     :scheme :http
			     :server-port (:port options))]
		   (try
		     (handler req)
		     (catch Exception e
		       (call-error-handler options req e)))
		   nil))
	       options)
    :upstream-error (upstream-stage error-stage-fn)))



