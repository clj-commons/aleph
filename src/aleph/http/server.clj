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
    [aleph core channel pipeline]
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
     URI
     URLConnection]))

(defn request-method
  "Get HTTP method from Netty request."
  [^HttpRequest req]
  {:request-method (->> req .getMethod .getName .toLowerCase keyword)})

(defn request-headers
  "Get headers from Netty request."
  [^HttpMessage req]
  (let [headers (into {} (.getHeaders req))
	host (-> req (.getHeader "Host") (.split ":"))]
    {:headers (into {}
		(map
		  (fn [[k v]] [(.toLowerCase k) v])
		  (into {} (.getHeaders req))))
     :server-name (first host)
     :server-port (second host)
     :keep-alive? (HttpHeaders/isKeepAlive req)}))

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
  (let [uri-parts (.split (.getUri req) "[?]" 2)]
    {:uri (first uri-parts)
     :query-string (second uri-parts)}))

(defn transform-request
  "Transforms a Netty request into a Ring request."
  [^HttpRequest req]
  (merge
    (request-method req)
    (request-body req)
    (request-headers req)
    (request-uri req)))

;;;

(defn call-error-handler
  "Calls the error-handling function."
  [options e]
  ((:error-handler options) e))

(defn response-listener
  "Handles the completion of the response."
  [options]
  (reify ChannelFutureListener
    (operationComplete [_ future]
      (if (.isSuccess future)
	(do
	  )
	(call-error-handler options (.getCause future))))))

(defn transform-response
  "Turns a Ring response into something Netty can understand."
  [response]
  (let [msg (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v] (:headers response)]
      (.addHeader msg k v))
    (when body
      (.setContent msg body))
    (HttpHeaders/setContentLength msg (-> msg .getContent .readableBytes))
    (when (:chunked? response)
      (.setChunked msg true))
    msg))

(defn respond-with-string
  ([channel response options]
     (respond-with-string channel response options "UTF-8"))
  ([channel response options charset]
     (let [body (ChannelBuffers/copiedBuffer (:body response) charset)
	   response (transform-response (assoc response :body body))]
       (-> channel
	 (.write response)
	 (.addListener (response-listener options))))))

(defn respond-with-sequence
  ([channel response options]
     (respond-with-sequence channel response options "UTF-8"))
  ([channel response options charset]
     (respond-with-string channel (update-in response [:body] #(apply str %)) options charset)))

(defn respond-with-stream
  [channel response options]
  (let [response (transform-response (update-in response [:body] #(input-stream->channel-buffer %)))]
    (-> channel
      (.write response)
      (.addListener (response-listener options)))))

;;TODO: use a more efficient file serving mechanism
(defn respond-with-file
  [channel response options]
  (let [file ^File (:body response)
	content-type (or (URLConnection/guessContentTypeFromName (.getName file)) "application/octet-stream")]
    (respond-with-stream
      channel
      (-> response
	(update-in [:headers "Content-Type"] #(or % content-type))
	(update-in [:body] #(FileInputStream. %)))
      options)))

;;;

(defn- error-stage-fn [evt]
  (when (instance? ExceptionEvent evt)
    (println "error-stage-fn")
    (.printStackTrace (.getCause evt)))
  evt)

(defn- respond [channel response options]
  (let [body (:body response)]
    (cond
      (string? body) (respond-with-string channel response options)
      (sequential? body) (respond-with-sequence channel response options)
      (instance? InputStream body) (respond-with-stream channel response options)
      (instance? File body) (respond-with-file channel response options))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [handler-channel (atom nil)
	reset-channels
	  (fn [netty-channel]
	    (let [[outer inner] (channel-pair)]
	      ;; Aleph -> Netty
	      (receive-in-order outer
		(fn [response]
		  (try
		    (respond netty-channel response options)
		    (catch Exception e
		      (.printStackTrace e)))))
	      ;; Netty -> Aleph
	      (receive inner
		(fn [request]
		  (handler inner request)))
	      (reset! handler-channel outer)))
	netty-pipeline
	  ^ChannelPipeline
	  (create-netty-pipeline
	    :decoder (HttpRequestDecoder.)
	    :encoder (HttpResponseEncoder.)
	    :deflater (HttpContentCompressor.)
	    :upstream-error (upstream-stage error-stage-fn)
	    :request (message-stage
		       (fn [^Channel netty-channel ^HttpRequest request]
			 ;; if this is a new request, create a new pair of channels
			 (let [ch @handler-channel]
			   (when (or (not ch) (sealed? ch))
			     (reset-channels netty-channel)))
			 ;; prime handler channel
			 (enqueue-and-close @handler-channel
			   (assoc (transform-request request)
			     :scheme :http
			     :remote-addr (->> netty-channel
					    .getRemoteAddress
					    .getAddress
					    .getHostAddress)))))
	    :downstream-error (downstream-stage error-stage-fn))]
    netty-pipeline))




