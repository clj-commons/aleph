;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.server.requests
  (:use
    [lamina core connections executors api]
    [aleph netty formats]
    [aleph.core lazy-map]
    [aleph.http core])
  (:import
    [java.io
     InputStream]
    [org.jboss.netty.handler.codec.http
     HttpHeaders
     HttpRequest
     HttpChunk]))

(defn- request-destination [_]
  (fn [msg]
    (let [headers (:headers msg)]
      (let [parts (.split (str (or (headers "host") "")) "[:]")]
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
      :body (if (.isChunked req)
	      ::chunked
	      (let [body (.getContent req)]
		(when (pos? (.readableBytes body))
		  body))))))

(defn wrap-response-channel [ch]
  (proxy-channel
    (fn [[rsp]]
      (let [body (:body rsp)]
	(if (or (channel? body) (instance? InputStream body))
	  (let [result (channel)]
	    [result [[result rsp]]])
	  (let [result (result-channel)]
	    [result [[result rsp]]]))))
    ch))

(defn pre-process-request [req options]
  (run-pipeline req
    #(process-chunks % options)
    #(decode-aleph-message % options)))

(defn http-server-generator [handler options]
  (let [handler (fn [ch req]
		  (let [c (constant-channel)]
		    (receive c #(enqueue ch (assoc % :keep-alive? (:keep-alive? req))))
		    (run-pipeline (dissoc req :keep-alive?)
		      :error-handler (fn [_])
		      :timeout (when-let [timeout (:timeout options)] (timeout req))
		      #(pre-process-request % options)
		      #(handler c %))))]
    (pipelined-server-generator handler
      (assoc options
        :include-request true
        :response-channel #(wrap-response-channel (constant-channel))))))

(defn request-handler [handler options]
  (let [timeout (:timeout options)
	f (executor
	    (:thread-pool options)
	    (fn [req]
	      (let [ch (wrap-response-channel (constant-channel))]
		(run-pipeline req
		  :error-handler (fn [_])
		  #(pre-process-request % options)
		  (fn [request]
                    (let [return-result (handler ch request)]
                      (if (result-channel? return-result)
                        (let [result (result-channel)]
                          (on-error return-result #(error! result %))
                          (siphon-result
                            (read-channel ch)
                            result))
                        (read-channel ch)))))))
	    options)]
    (fn [netty-channel req]
      (let [req (transform-netty-request req netty-channel options)]
	(f [req] (when timeout {:timeout (timeout req)}))))))

(defn consume-request-stream [^Channel netty-channel in server-generator options]
  (let [[a b] (channel-pair)]

    (run-pipeline in
      read-channel
      (fn [req]
	(when req
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
                             (take-while* #(not= ::last %)))]
                (enqueue a (assoc req :body chunks))
                (run-pipeline (closed-result chunks)
                  (fn [_]
                    keep-alive?)))))))
      (fn [keep-alive?]
	(if keep-alive?
	  (restart)
	  (close a))))

    (server-generator b)
    
    a))

