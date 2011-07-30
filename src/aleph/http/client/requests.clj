(ns aleph.http.client.requests
  (:use
    [lamina core]
    [aleph formats]
    [aleph.http core utils])
  (:import
    [org.jboss.netty.handler.codec.http
     DefaultHttpRequest
     HttpRequest
     HttpChunk
     DefaultHttpChunk
     HttpVersion]
    [java.net
     URI]))

(defn transform-aleph-request [request scheme ^String host ^Integer port options]
  (let [uri (URI. scheme nil host port (:uri request) (:query-string request) (:fragment request))
        req (DefaultHttpRequest.
	      HttpVersion/HTTP_1_1
	      (keyword->request-method (:request-method request))
	      (str
		(when-not (= \/ (-> uri .getPath first))
		  "/")
		(.getPath uri)
		(when-not (empty? (.getQuery uri))
		  "?")
		(.getQuery uri)))
	body (:body request)]
    (.setHeader req "Host" (str host ":" port))
    (.setHeader req "Accept-Encoding" "gzip")
    (transform-aleph-message req request options)))

(defn wrap-request-stream [options in]
  (let [out (channel)]
    (on-closed out #(close in))
    (run-pipeline
      (receive-in-order in
	(fn [request]
	  (when request
	    (let [request (-> request
			    wrap-client-request
			    (pre-process-aleph-message options))
		  netty-request (transform-aleph-request
				  request
				  (:scheme options)
				  (:server-name options)
				  (:server-port options)
				  options)]
	      (enqueue out netty-request)
	      (when (channel? (:body request))
		(run-pipeline
		  (receive-in-order (:body request)
		    (fn [chunk]
		      (enqueue out (-> request
				     (assoc :body chunk)
				     (encode-aleph-message options)
				     :body
				     bytes->channel-buffer
				     DefaultHttpChunk.))))
		  (fn [_]
		    (enqueue out HttpChunk/LAST_CHUNK))))))))
      (fn [_]
	(close out)))
    out))

