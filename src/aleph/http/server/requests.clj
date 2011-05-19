;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.server.requests
  (:use
    [lamina.core.pipeline :only (closed-result)]
    [lamina core connections executors]
    [aleph netty]
    [aleph.core lazy-map]
    [aleph.http core])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpChunk]))

(defn- request-destination [_]
  (fn [msg]
    (let [headers (:headers msg)]
      (let [parts (.split ^String (headers "host") "[:]")]
	{:server-name (first parts)
	 :server-port (when-let [port (second parts)]
			(Integer/parseInt port))}))))

(defn transform-netty-request
  "Transforms a Netty request into a Ring request."
  [^HttpRequest req netty-channel options]
  (let [destination (delayed (request-destination req))
	headers (delayed (netty-headers req))
	content-info (delayed (content-info req))
	content-length (delayed (content-length req))
	request-method (delayed (netty-request-method req))
	uri (delayed (netty-request-uri req))
	request (lazy-map
		  :scheme :http
		  :remote-addr (channel-origin netty-channel)
		  :headers headers
		  :server-name destination
		  :uri uri
		  :query-string uri
		  :server-port destination
		  :content-type content-info
		  :content-encoding content-info
		  :request-method request-method)]
    (assoc request
      :body (let [body (-> request
			 (assoc :body (.getContent req))
 			 (decode-aleph-message options)
 			 :body)]
	      (if (final-netty-message? req)
		body
		(let [ch (channel)]
		  (when body
		    (enqueue ch body))
		  ch))))))

(defn wrap-response-channel [ch]
  (proxy-channel
    (fn [[rsp]]
      (if (channel? (:body rsp))
	(let [result (channel)]
	  [result [[result rsp]]])
	(let [result (result-channel)]
	  [result [[result rsp]]])))
    ch))

(defn handle-request [netty-channel req handler options]
  (let [ch (wrap-response-channel (constant-channel))
	req (transform-netty-request req netty-channel options)]
    (with-thread-pool (:thread-pool options) {:timeout ((:timeout options) req)}
      (handler ch req))
    ch))

(defn consume-request-stream [netty-channel in handler options]
  (let [[a b] (channel-pair)]
    (run-pipeline in
      read-channel
      (fn [req]
	(let [req (transform-netty-request req netty-channel options)]
	  (enqueue a req)
	  (when (-> req :body channel?)
	    (let [chunks (->> in
			   (take-while* #(instance? HttpChunk %))
			   (map* #(when-not (final-netty-message? %)
				    (-> req
				      (assoc :body (.getContent ^HttpChunk %))
				      (decode-aleph-message options)
				      :body)))
			   (filter* (complement nil?)))]
	      (siphon chunks (:body req))
	      (closed-result chunks)))))
      (fn [_]
	(restart)))
    (server b handler
      (assoc options
	:response-channel #(wrap-response-channel (constant-channel))))
    a))

