;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http
  (:use [aleph.core]
    [clojure.pprint])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest HttpMessage HttpMethod HttpHeaders HttpHeaders$Names
     HttpChunk DefaultHttpChunk
     DefaultHttpResponse HttpVersion HttpResponseStatus
     HttpRequestDecoder HttpResponseEncoder HttpContentCompressor]
    [org.jboss.netty.channel
     Channel ChannelPipeline MessageEvent ChannelFutureListener Channels]
    [org.jboss.netty.buffer
     ChannelBuffer ChannelBuffers ChannelBufferInputStream]
    [java.util
     Map$Entry]
    [java.io
     ByteArrayInputStream InputStream File FileInputStream]))

(defn transform-headers [headers]
  (->> headers
    (map #(list (.getKey ^Map$Entry %) (.getValue ^Map$Entry %)))
    flatten
    (apply hash-map)))

(defn request-method [^HttpRequest msg]
  (->> msg .getMethod .getName .toLowerCase keyword))

(defn channel-buffer->input-stream [^ChannelBuffer buf]
  (ChannelBufferInputStream. buf))

(defn request-headers [^HttpMessage req]
  {:headers (transform-headers (.getHeaders req))
   :chunked? (.isChunked req)
   :keep-alive? (HttpHeaders/isKeepAlive req)
   :last-chunk (and (.isChunked req) (.isLast ^HttpChunk req))})

(defn request-body [^HttpMessage req]
  (let [content-length (HttpHeaders/getContentLength req)
	has-content? (pos? content-length)]
    (when has-content?
      {:content-length content-length
       :body (channel-buffer->input-stream (.getContent req))
       })))

(defn request-uri [^HttpMessage req]
  {:uri (.getUri req)})

(defn transform-request [^HttpRequest req]
  (merge
    (request-body req)
    (request-headers req)
    (request-uri req)))

;;;

(defn input-stream->channel-buffer [^InputStream stream]
  (let [ary (make-array Byte/TYPE (.available stream))]
    (.read stream ary)
    (ChannelBuffers/wrappedBuffer ary)))

(defn to-channel-buffer
  ([body]
     (to-channel-buffer body "UTF-8"))
  ([body charset]
     (cond
       (string? body) (ChannelBuffers/copiedBuffer ^String body charset)
       (sequential? body) (ChannelBuffers/copiedBuffer ^String (apply str body) charset)
       (instance? File body) (input-stream->channel-buffer (FileInputStream. body))
       (instance? InputStream body) (input-stream->channel-buffer body)
       :else body)))

(defn transform-response
  [request response]
  (let [msg (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))
	body (:body response)]
    (doseq [[k v] (:headers response)]
      (.addHeader msg k v))
    (.setContent msg (to-channel-buffer body))
    (when (:keep-alive? request)
      (.addHeader msg HttpHeaders$Names/CONTENT_LENGTH (-> msg .getContent .readableBytes)))
    msg))

;;;

(defn response-listener [req]
  (if (HttpHeaders/isKeepAlive req)
    (reify ChannelFutureListener
      (operationComplete [_ future]
	))
    ChannelFutureListener/CLOSE))

(defn request-handler [handler]
  (upstream-stage
    (fn [evt]
      (when-let [req ^HttpRequest (event-message evt)]
	(handler
	  (merge
	    (transform-request req)
	    {:channel (.getChannel ^MessageEvent evt)
	     :respond (fn [this msg]
			(.getPipeline (:channel this))
			(-> (:channel this)
			  (Channels/write msg)
			  (.addListener (response-listener req))))}))))))

(defn http-pipeline [port handler]
  (create-pipeline
    :decoder (HttpRequestDecoder.)
    :encoder (HttpResponseEncoder.)
    :deflater (HttpContentCompressor.)
    :request (request-handler
	       #(handler (assoc %
			   :scheme :http
			   :port port)))))



