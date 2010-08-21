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
    [aleph.http utils]
    [aleph.core channel pipeline]
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
    [org.jboss.netty.handler.stream
     ChunkedFile
     ChunkedWriteHandler]
    [org.jboss.netty.handler.codec.http.websocket
     WebSocketFrameEncoder
     WebSocketFrameDecoder
     WebSocketFrame
     DefaultWebSocketFrame]
    [java.security
     MessageDigest]
    [java.nio.charset
     Charset]
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
	host (-> req (.getHeader "host") (.split ":"))
	headers (into {}
		  (map
		    (fn [[^String k v]] [(.toLowerCase k) v])
		    (into {} (.getHeaders req))))]
    {:headers headers
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
       :body (channel-buffer->input-stream (.getContent req))})))

(defn request-uri
  "Get URI from Netty request."
  [^HttpRequest req]
  (let [paths (.split (.getUri req) "[?]")]
    {:uri (first paths)
     :query-string (second paths)}))

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
      (when (:close? options)
	(Channels/close (.getChannel future)))
      (when-not (.isSuccess future)
	(call-error-handler options (.getCause future))))))

(defn transform-response
  "Turns a Ring response into something Netty can understand."
  [response options]
  (let [;;response (wrap-response response)
	rsp (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v-or-vals] (:headers response)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (.addHeader rsp (to-str k) v-or-vals)
	  (doseq [val v-or-vals]
	    (.addHeader rsp (to-str k) val)))))
    (when body
      (.setContent rsp body))
    (HttpHeaders/setContentLength rsp (-> rsp .getContent .readableBytes))
    (when (:keep-alive? options)
      (.setHeader rsp "connection" "keep-alive"))
    rsp))

(defn respond-with-string
  ([^Channel channel response options]
     (respond-with-string channel response options "UTF-8"))
  ([^Channel channel response options ^String charset]
     (let [body (ChannelBuffers/copiedBuffer
		  ^CharSequence (:body response)
		  (java.nio.charset.Charset/forName charset))
	   response (transform-response
		      (-> response
			(update-in [:headers] assoc "charset" charset)
			(assoc :body body))
		      options)]
       (-> channel
	 (.write response)
	 (.addListener (response-listener options))))))

(defn respond-with-sequence
  ([channel response options]
     (respond-with-sequence channel response options "UTF-8"))
  ([channel response options charset]
     (respond-with-string channel (update-in response [:body] #(apply str %)) options charset)))

(defn respond-with-stream
  [^Channel channel response options]
  (let [response (transform-response
		   (update-in response [:body] #(input-stream->channel-buffer %))
		   options)]
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
	(update-in [:headers "content-type"] #(or % content-type))
	(update-in [:body] #(FileInputStream. ^File %)))
      options)))

(defn- respond [channel response options]
  (try
    (let [body (:body response)]
      (cond
       (string? body) (respond-with-string channel response options)
       (sequential? body) (respond-with-sequence channel response options)
       (instance? InputStream body) (respond-with-stream channel response options)
       (instance? File body) (respond-with-file channel response options)))
    (catch Exception e
      (.printStackTrace e))))

;;;

(defn websocket-handshake? [^HttpRequest request]
  (and
    (= "upgrade" (.toLowerCase (.getHeader request "connection")))
    (= "websocket" (.toLowerCase (.getHeader request "upgrade")))))

(defn transform-key [k]
  (/
    (-> k (.replaceAll "[^0-9]" "") Long/parseLong)
    (-> k (.replaceAll "[^ ]" "") .length)))

(defn secure-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "sec-websocket-origin" (headers "origin"))
  (.addHeader response "sec-websocket-location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "sec-websocket-protocol")]
    (.addHeader response "sec-websocket-protocol" protocol))
  (let [buf (ChannelBuffers/buffer 16)]
    (doto buf
      (.writeInt (transform-key (headers "sec-websocket-key1")))
      (.writeInt (transform-key (headers "sec-websocket-key2")))
      (.writeLong (-> request .getContent .readLong)))
    (.setContent response
      (-> (MessageDigest/getInstance "MD5")
	(.digest (.array buf))
	ChannelBuffers/wrappedBuffer))))

(defn standard-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "websocket-origin" (headers "origin"))
  (.addHeader response "websocket-location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "websocket-protocol")]
    (.addHeader response "websocket-protocol" protocol)))

(defn websocket-response [^HttpRequest request]
  (let [response (DefaultHttpResponse.
		   HttpVersion/HTTP_1_1
		   (HttpResponseStatus. 101 "Web Socket Protocol Handshake"))
	headers (:headers (request-headers request))]
    (.addHeader response "Upgrade" "WebSocket")
    (.addHeader response "Connection" "Upgrade")
    (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
      (secure-websocket-response request headers response)
      (standard-websocket-response request headers response))
    response))

(defn respond-to-handshake [ctx ^HttpRequest request]
  (let [pipeline (-> ctx .getChannel .getPipeline)]
    (.replace pipeline "decoder" "websocket-decoder" (WebSocketFrameDecoder.))
    (-> ctx .getChannel (.write (websocket-response request)))
    (.replace pipeline "encoder" "websocket-encoder" (WebSocketFrameEncoder.))))

(defn from-websocket-frame [^WebSocketFrame frame]
  (.toString frame))

(defn to-websocket-frame [msg]
  (DefaultWebSocketFrame. msg))

(defn websocket-handshake-handler [handler options]
  (let [[inner outer] (channel-pair)]
    (reify ChannelUpstreamHandler
      (handleUpstream [_ ctx evt]
	(if-let [msg (message-event evt)]
	  (cond
	    (instance? WebSocketFrame msg) (enqueue outer (from-websocket-frame msg))
	    (instance? HttpRequest msg) (if (websocket-handshake? msg)
					  (let [ch (.getChannel ctx)]
					    (receive-all outer
					      #(.write ch (to-websocket-frame %)))
					    (respond-to-handshake ctx msg)
					    (handler inner {:websocket true}))
					  (.sendUpstream ctx evt)))
	  (.sendUpstream ctx evt))))))

;;;

(defn http-session-handler [handler options]
  (let [handler-channel (atom nil)
	reset-channels (fn [connection-options netty-channel]
			 (let [[outer inner] (channel-pair)
			       keep-alive? (:keep-alive? connection-options)]
			   ;; Aleph -> Netty
			   (receive-all outer
			     (fn [response]  
			       (respond netty-channel
				 response
				 (merge
				   options
				   connection-options
				   {:close? (and (not keep-alive?) (closed? outer))}))))
			   ;; Netty -> Aleph
			   (receive inner
			     (fn [request]
			       (handler inner request)))
			   (reset! handler-channel outer)))]
    (message-stage
      (fn [^Channel netty-channel ^HttpRequest request]
	(try
	  ;; if this is a new request, create a new pair of channels
	  (let [ch @handler-channel]
	    (when (or (not ch) (sealed? ch))
	      (reset-channels
		{:keep-alive? (.isKeepAlive request)}
		netty-channel)))
	  ;; prime handler channel
	  (enqueue-and-close @handler-channel
	    (assoc (transform-request request)
	      :scheme :http
	      :remote-addr (->> netty-channel
			     .getRemoteAddress
			     .getAddress
			     .getHostAddress)))
	  (catch Exception e
	    (.printStackTrace e)))))))

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
  "Starts an HTTP server."
  [handler options]
  (start-server
    #(create-pipeline handler options)
    (assoc options
      :error-handler (fn [^Throwable e] (.printStackTrace e)))))




