;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.server.responses
  (:use
    [lamina core]
    [aleph formats netty]
    [aleph.http core utils])
  (:import
    [org.jboss.netty.channel
     Channel]
    [org.jboss.netty.handler.codec.http
     DefaultHttpResponse
     DefaultHttpChunk
     HttpChunk
     HttpResponse
     HttpResponseStatus
     HttpVersion]
    [java.io
     File
     InputStream
     RandomAccessFile]
    [java.net
     URLConnection]))

;;;

(defn transform-aleph-response
  "Turns a Ring response into something Netty can understand."
  [response options]
  (let [response (wrap-response response)
	rsp (DefaultHttpResponse.
	      HttpVersion/HTTP_1_1
	      (HttpResponseStatus/valueOf (:status response)))]
    (transform-aleph-message rsp
      (-> response
	(update-in [:headers "Connection"]
	  #(or %
	     (if (:keep-alive? response)
	       "keep-alive"
	       "close")))
	(update-in [:headers "Content-Type"]
	  #(or %
	     (str
	       (:content-type response)
	       (when-let [charset (:character-encoding response)]
		 (str "; charset=" charset))))))
      options)))

;;;

(defn siphon-result* [src dst]
  (when (and (result-channel? src) (result-channel? dst))
    (siphon-result src dst)))

;;;

(defn- respond-with-string
  ([^Channel netty-channel options returned-result response]
     (let [response (update-in response [:character-encoding]
		      #(or % "utf-8"))	   
	   body (-> response
		  :body
		  (bytes->channel-buffer (:character-encoding response)))
	   response (transform-aleph-response
		      (assoc response :body body)
		      options)]
       (siphon-result*
	 (write-to-channel netty-channel response false)
	 returned-result))))

(defn- respond-with-sequence
  ([netty-channel options returned-result response]
     (respond-with-string netty-channel options returned-result
       (update-in response [:body] #(apply str %)))))

;;;

(defn- respond-with-file
  [netty-channel options returned-result response]
  (let [file ^File (:body response)
	content-type (or
		       (URLConnection/guessContentTypeFromName (.getName file))
		       "application/octet-stream")
	fc (.getChannel (RandomAccessFile. file "r"))
	response (-> response
		   (update-in [:content-type] #(or % content-type))
		   (assoc :body fc)
		   (transform-aleph-response options))]
    (siphon-result*
      (write-to-channel netty-channel response false
	:on-write #(.close fc))
      returned-result)))

;;;

(defn- respond-with-channel
  [^Channel netty-channel options returned-result response]
  (let [response (update-in response [:character-encoding] #(or % "utf-8"))
	initial-response ^HttpResponse (transform-aleph-response response options)
	ch (:body response)
	write-to-channel (fn [& args]
			   (let [result (apply write-to-channel args)]
			     (enqueue returned-result result)
			     result))]
    (run-pipeline (.getCloseFuture netty-channel)
      wrap-netty-channel-future
      (fn [_]
	(close ch)))
    (run-pipeline (let [result (write-to-channel netty-channel initial-response false)]
		    (enqueue returned-result result)
		    result)
      (fn [_]
	(receive-in-order
	  (map*
	    #(-> response
	       (assoc :body %)
	       (encode-aleph-message options)
	       :body
	       bytes->channel-buffer
	       DefaultHttpChunk.)
	    ch)
	  (fn [msg]
	    (enqueue returned-result (write-to-channel netty-channel msg false))
	    nil)))
      (fn [_]
	(enqueue-and-close returned-result
	  (write-to-channel netty-channel HttpChunk/LAST_CHUNK false))))))

;;;

(defn- respond-with-stream
  [^Channel netty-channel options returned-result response]
  (let [stream ^InputStream (:body response)
	ch (input-stream->channel stream (or (:chunk-size response) (:chunk-size options) 8192))]
    (on-closed ch #(.close stream))
    (respond-with-channel netty-channel options returned-result (assoc response :body ch))))

;;;

(defn- respond-with-channel-buffer
  [netty-channel options returned-result response]
  (let [response (update-in response [:content-type] #(or % "application/octet-stream"))]
    (siphon-result*
      (write-to-channel netty-channel
	(transform-aleph-response response options)
	false)
      returned-result)))

;;;

(defn respond [^Channel netty-channel options returned-result response]
  (let [response (pre-process-aleph-message response options)
	response (update-in response [:headers] (partial merge {"Server" "aleph (0.2.0)"}))
	response (merge ((content-info nil) response) response)
	body (:body response)]
    (cond
      (nil? body)
      (respond-with-string netty-channel options returned-result (assoc response :body ""))

      (string? body)
      (respond-with-string netty-channel options returned-result response)

      (channel? body)
      (respond-with-channel netty-channel options returned-result response)

      (instance? InputStream body)
      (respond-with-stream netty-channel options returned-result response)

      (instance? File body)
      (respond-with-file netty-channel options returned-result response)

      :else
      (let [response (encode-aleph-message response options)
	    original-body body
	    body (:body response)
	    options (assoc options :auto-transform false)]
	(cond
	  (sequential? body)
	  (respond-with-sequence netty-channel options returned-result response)
	  
	  (bytes? body)
	  (respond-with-channel-buffer netty-channel options returned-result (update-in response [:body] bytes->channel-buffer))
	  
	  :else
	  (throw (Exception. (str "Don't know how to respond with body of type " (prn-str original-body) (class body)))))))))

