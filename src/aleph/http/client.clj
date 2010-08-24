;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.client
  (:use
    [aleph netty formats]
    [aleph.http core utils]
    [aleph.core channel pipeline]
    [clojure.contrib.json])
  (:require
    [clj-http.client :as client])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     HttpResponseStatus
     HttpClientCodec
     HttpChunk
     HttpContentDecompressor
     DefaultHttpRequest
     HttpMessage
     HttpMethod
     HttpHeaders
     HttpVersion]
    [org.jboss.netty.channel
     ExceptionEvent]
    [java.net
     URI]))

;;;

(defn create-pipeline [ch close? options]
  (let [headers (atom nil)]
    (create-netty-pipeline
      :codec (HttpClientCodec.)
      :inflater (HttpContentDecompressor.)
      :upstream-error (upstream-stage error-stage-handler)
      :response (message-stage
		  (fn [netty-channel response]
		    (when-not @headers
		      (reset! headers (netty-headers response)))
		    (let [response (if (instance? HttpResponse response)
				     (transform-netty-response response @headers)
				     (transform-netty-chunk response @headers))]
		      (enqueue ch response)
		      (when (close? response)
			(.close netty-channel))
		      (when (final-netty-message? response)
			(reset! headers nil)))))
      :downstream-error (downstream-stage error-stage-handler))))

;;;

(defprotocol HttpClient
  (create-request-channel [c])
  (close-http-client [c]))

(defn raw-http-client
  "Create an HTTP client."
  [options]
  (let [options (split-url options)
	client (create-client
		 #(create-pipeline
		    %
		    (or (:close? options) (constantly false))
		    options)
		 #(transform-aleph-request
		    (:scheme options)
		    (:server-name options)
		    (:server-port options)
		    %)
		 options)]
    (reify HttpClient
      (create-request-channel [_]
	client)
      (close-http-client [_]
	(enqueue-and-close (-> client run-pipeline wait-for-pipeline) nil)))))

(defn http-request
  ([request]
     (http-request
       (raw-http-client request)
       request))
  ([client request]
     (run-pipeline client
       create-request-channel
       (fn [ch]
	 (enqueue ch request)
	 ch))))


