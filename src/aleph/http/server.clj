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
    [aleph.http.server requests responses]
    [lamina core executors trace]
    [lamina.core.pipeline :only (success-result)]
    [clojure.pprint])
  (:require
    [clojure.contrib.logging :as log]
    [clojure.string :as str])
  (:import
    [org.jboss.netty.handler.codec.http
     DefaultHttpResponse
     HttpResponseStatus
     HttpVersion
     HttpRequest
     HttpHeaders
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel
     ChannelPipeline]))


;;;

(def continue-response
  (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/CONTINUE))

(defn http-session-handler [handler options]
  (let [init? (atom false)
 	ch (channel)]
    (message-stage
      (fn [^Channel netty-channel request]
	(when (and
		(instance? HttpRequest request)
		(= "100-continue" (.getHeader ^HttpRequest request "Expect")))
	  (.write netty-channel continue-response))
	(if-not (or @init? (.isChunked ^HttpRequest request) (HttpHeaders/isKeepAlive request))
	  (run-pipeline (handle-request netty-channel request handler options)
	    :error-handler (fn [ex]
			     (log/error "Error in handler, closing connection" ex)
			     (.close netty-channel))
	    read-channel
	    #(respond netty-channel options (first %) (second %))
	    (fn [_] (.close netty-channel)))
	  (do
	    (when (compare-and-set! init? false true)
	      (run-pipeline
		(receive-in-order (consume-request-stream netty-channel ch handler options)
		  #(respond netty-channel options (first %) (second %)))
		(fn [_] (.close netty-channel))))
	    (enqueue ch request)))
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
  (let [options (merge
		  {:timeout (constantly -1)
		   :name (str "http-server." (:port options))}
		  options
		  {:thread-pool (when (and
					(contains? options :thread-pool)
					(not (nil? (:thread-pool options))))
				  (thread-pool
				    (merge-with #(if (map? %1) (merge %1 %2) %2)
				      {:name (str "http-server." (:port options) ".thread-pool")}
				      (:thread-pool options))))})
	stop-fn (start-server
		  #(create-pipeline handler options)
		  options)]
    (fn []
      (async
	(try
	  (stop-fn)
	  (finally
	    (when-let [thread-pool (:thread-pool options)]
	      (shutdown-thread-pool thread-pool))))))))






