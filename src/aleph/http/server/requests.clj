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
     HttpHeaders
     HttpRequest
     HttpChunk]))

(defn- request-destination [_]
  (fn [msg]
    (let [headers (:headers msg)]
      (let [parts (.split ^String (or (headers "host") "") "[:]")]
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
		  :character-encoding content-info
		  :content-length content-length
		  :request-method request-method)]
    (assoc request
      :body (if (final-netty-message? req)
	      (-> request
		(assoc :body (.getContent req))
		(decode-aleph-message options)
		:body)
	      ::chunked))))

(defn wrap-response-channel [ch]
  (proxy-channel
    (fn [[rsp]]
      (if (channel? (:body rsp))
	(let [result (channel)]
	  [result [[result rsp]]])
	(let [result (result-channel)]
	  [result [[result rsp]]])))
    ch))

(defn request-handler [handler options]
  (let [f (executor (:pool options)
	    (fn [netty-channel req]
	        (let [ch (wrap-response-channel (constant-channel))
		      req (transform-netty-request req netty-channel options)]
		  (handler ch req)
		  (read-channel ch)))
	    options)]
    #(f [%1 %2])))

(defn consume-request-stream [netty-channel in handler options]
  (let [[a b] (channel-pair)
	handler (fn [ch req]
		  (let [c (constant-channel)]
		    (handler c (dissoc req :keep-alive?))
		    (receive c #(enqueue ch (assoc % :keep-alive? (:keep-alive? req))))))]
    (run-pipeline in
      read-channel
      (fn [req]
	(let [keep-alive? (HttpHeaders/isKeepAlive req)
	      req (transform-netty-request req netty-channel options)
	      req (assoc req :keep-alive? keep-alive?)]
	  (if-not (= ::chunked (:body req))
	    (do
	      (enqueue a req)
	      keep-alive?)
	    (let [chunks (->> in
			   (take-while* #(instance? HttpChunk %))
			   (map* #(if (final-netty-message? %)
				    ::last
				    (.getContent ^HttpChunk %)))
			   (take-while* #(not= ::last %)))
		  chunks (map* #(-> req (assoc :body %) (decode-aleph-message options) :body) chunks)]
	      (enqueue a (assoc req :body chunks))
	      (run-pipeline (closed-result chunks)
		(fn [_]
		  keep-alive?))))))
      (fn [keep-alive?]
	(if keep-alive?
	  (restart)
	  (close a))))
    (server b handler
      (assoc options
	:response-channel #(wrap-response-channel (constant-channel))))
    a))

