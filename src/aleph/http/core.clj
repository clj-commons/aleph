;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.http.core
  (:use
    [potemkin]
    [aleph netty formats]
    [aleph.http utils]
    [lamina.core]
    [gloss io])
  (:require
    [clojure.string :as str])
  (:import
    [org.jboss.netty.channel
     ChannelFutureListener
     Channels]
    [org.jboss.netty.handler.codec.http
     DefaultHttpResponse
     DefaultHttpRequest
     HttpResponseStatus
     HttpMethod
     HttpVersion
     HttpHeaders
     HttpRequest
     HttpResponse
     HttpMessage
     HttpChunk]
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBuffers]
    [java.io
     InputStreamReader]
    [java.net
     URI]
    [java.nio.charset
     Charset]
    [java.nio.channels
     FileChannel
     FileChannel$MapMode]))

;;;

(def-custom-map LazyMap
  :get
  (fn [_ data _ key default-value]
    `(if-not (contains? ~data ~key)
       ~default-value
       (let [val# (get ~data ~key)]
         (if (delay? val#)
           @val#
           val#)))))

(defn lazy-map [& {:as m}]
  (LazyMap. m))

;;;

(defn encode-aleph-message [aleph-msg options]
  (let [auto-transform? (:auto-transform options)
	headers (:headers aleph-msg)
	body (:body aleph-msg)
	content-type (or (:content-type aleph-msg) "text/plain")
	charset (or (:character-encoding aleph-msg) "utf-8")
	auto-transform? (or (:auto-transform aleph-msg) (:auto-transform options))]

    (cond

      (and auto-transform? (.startsWith ^String content-type "application/json") (coll? body))
      (update-in aleph-msg [:body] encode-json->bytes)

      (and auto-transform? (.startsWith ^String content-type "application/xml") (coll? body))
      (update-in aleph-msg [:body] #(encode-xml->bytes % charset))

      (instance? FileChannel body)
      (let [fc ^FileChannel body]
	(assoc-in aleph-msg [:body]
	  (ChannelBuffers/wrappedBuffer (.map fc FileChannel$MapMode/READ_ONLY 0 (.size fc)))))
      
      (bytes? body)
      (update-in aleph-msg [:body] #(bytes->channel-buffer % charset))

      :else
      aleph-msg)))

(defn decode-aleph-message [aleph-msg options]
  (let [body (:body aleph-msg)]
    (cond

      (channel? body)
      aleph-msg
      
      (or
	  (nil? body)
	  (and (sequential? body) (empty? body))
	  (zero? (.readableBytes ^ChannelBuffer body)))
      (assoc aleph-msg :body nil)

      :else
      (let [auto-transform? (:auto-transform options)
	    headers (:headers aleph-msg)
	    content-type (or (:content-type aleph-msg) "text/plain")
	    charset (or (:character-encoding aleph-msg) "utf-8")]
	
	(cond
	  
	  (and auto-transform? (.startsWith ^String content-type "application/json"))
	  (update-in aleph-msg [:body] decode-json)
	  
	  (and auto-transform? (.startsWith ^String content-type "application/xml"))
	  (update-in aleph-msg [:body] decode-xml)
	  
	  (and auto-transform? (.startsWith ^String content-type "text"))
	  (update-in aleph-msg [:body] #(bytes->string % charset))

	  (and auto-transform? (.startsWith ^String content-type "application/x-www-form-urlencoded"))
	  (update-in aleph-msg [:body] #(split-body-params % charset options))
	  
	  :else
	  aleph-msg)))))

(defn final-netty-message? [msg]
  (or
    (and (instance? HttpChunk msg) (.isLast ^HttpChunk msg))
    (and (instance? HttpMessage msg) (not (.isChunked ^HttpMessage msg)))))

;;;

(defn pre-process-aleph-message [msg options]
  (update-in msg [:headers]
    (fn [headers]
      (if headers
        (zipmap
	(map #(->> (str/split (to-str %) #"-") (map str/capitalize) (str/join "-")) (keys headers))
	(vals headers))))))

(defn process-chunks [req options]
  (if (:auto-transform options)
    (if (-> req :body channel?)
      (run-pipeline (reduce* conj [] (:body req))
	#(assoc req :body (bytes->channel-buffer %)))
      req)
    (if (-> req :body channel?)
      (let [stream (:body req)
	    stream (if-let [frame (create-frame
				    (:frame options)
				    (:delimiters options)
				    (:strip-delimiters? options))]
		     (decode-channel (map* bytes->byte-buffers stream) frame)
		     stream)]
	(assoc req :body stream))
      req)))

(defn transform-aleph-message [^HttpMessage netty-msg msg options]
  (let [body (:body msg)]
    (doseq [[k v-or-vals] (:headers msg)]
      (when-not (nil? v-or-vals)
	(if (string? v-or-vals)
	  (when-not (empty? v-or-vals)
	    (.addHeader netty-msg (to-str k) v-or-vals))
	  (doseq [val v-or-vals]
	    (when-not (empty? val)
	      (.addHeader netty-msg (to-str k) val))))))
    (if body
      (if (channel? body)
	(.setHeader netty-msg "Transfer-Encoding" "chunked")
	(do
	  (.setContent netty-msg (:body (encode-aleph-message msg options)))
	  (HttpHeaders/setContentLength netty-msg (-> netty-msg .getContent .readableBytes))))
      (HttpHeaders/setContentLength netty-msg 0))
    netty-msg))

