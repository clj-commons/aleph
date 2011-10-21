;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.client.responses
  (:use
    [lamina.core.pipeline :only (closed-result)]
    [lamina core]
    [aleph formats]
    [aleph.http core]
    [aleph.core lazy-map])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpResponse
     HttpChunk]))

(defn transform-netty-response [^HttpResponse netty-response options]
  (let [headers (delayed (netty-headers netty-response))
	content-info (delayed (content-info netty-response))
	content-length (delayed (content-length netty-response))
	response (lazy-map
		   :headers headers
		   :content-encoding content-info
		   :content-type content-info
		   :content-length content-length
		   :status (-> netty-response .getStatus .getCode))]
    (assoc response
      :body (.getContent netty-response))))

(defn pre-process-response [rsp options]
  (run-pipeline rsp
    #(process-chunks % options)
    #(decode-aleph-message % options)))

(defn wrap-response-stream [options in]
  (let [out (channel)
        cnt (atom 0)]
    (run-pipeline
      (receive-in-order in
	(fn [^HttpResponse response]
	  (let [chunked? (.isChunked response)
		response (transform-netty-response response options)]
	    (if-not chunked?
	      (enqueue out (pre-process-response response options))
	      (let [chunks (->> in
			     (take-while* #(instance? HttpChunk %))
			     (take-while* #(not (final-netty-message? %)))
			     (map* #(.getContent ^HttpChunk %)))
		    close-channel (constant-channel)
		    stream (splice chunks close-channel)]
		(receive close-channel
		  (fn [_] (close in)))
		(run-pipeline (assoc response :body stream)
		  #(pre-process-response % options)
		  #(enqueue out %)
		  (fn [_] (closed-result chunks))))))))
      (fn [_]
	(close out)))
    out))

