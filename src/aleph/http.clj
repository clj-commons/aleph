;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
  aleph.http
  (:use
    [aleph formats]
    [lamina.core]
    [potemkin])
  (:require
    [aleph.http.server :as server]
    [aleph.http.client :as client]
    [aleph.http.utils :as utils]
    [aleph.http.policy-file :as policy])
  (:import
    [java.io InputStream]))

(import-fn server/start-http-server)
(import-fn client/http-client)
(import-fn client/pipelined-http-client)
(import-fn client/http-request)
(import-fn client/websocket-client)

(defn sync-http-request
  "A synchronous version of http-request.  Halts the thread until the response has returned,
   and throws an exception if the timeout elapsed or another error occurred."
  ([request]
     (sync-http-request request -1))
  ([request timeout]
     (-> (http-request request timeout)
       (wait-for-result timeout))))

(import-fn policy/start-policy-file-server)

(defn wrap-aleph-handler
  "Allows for an asynchronous handler to be used within a largely synchronous application.
   Assuming the top-level handler has been wrapped in wrap-ring-handler, this function can be
   used to wrap handler functions for asynchronous routes."
  [f]
  (fn [request]
    (f (:channel request) (dissoc request :channel))
    {:status 200
     ::ignore true}))

(defn request-params
  "Returns a result-channel representing the merged query and body parameters in the request.

   The result-channel will be immediately realized if the request is not chunked, but if the
   request is chunked then synchronously waiting on the result inside the handler will cause
   issues.  You may synchrously wait on the result in a different thread, but the recommended
   approach is do something like:

   (run-pipeline (request-params request)
     (fn [params]
       ... handle request here ...))

   or to wrap your code in the (async ...) macro."
  ([request]
     (request-params request nil))
  ([request options]
     (run-pipeline (utils/body-params request options)
       #(merge (utils/query-params request options) %))))

(defn request-cookie
  "Returns a hash of the values within the request's cookie."
  [request]
  (utils/cookie->hash (get-in request [:headers "cookie"])))

(defn request-client-info
  "Returns information about the client, based on the 'User-Agent' header in the request."
  [request]
  (utils/parse-user-agent (get-in request [:headers "user-agent"])))

(defn request-body->input-stream
  "Returns a result-channel which will emit the request with an InputStream or nil as the
   body.

   The result-channel will be immediately realized if the request is not chunked, but if
   the request is chunked then synchronously waiting on the result inside the handler will
   cause issues.  You may synchronously wait on the result in a different thread, but the
   recommended approach is to structure your handler like:

   (defn handler [ch request]
     (run-pipeline (request-body->input-stream request)
       (fn [request]
         ... middleware and routing goes here ...)))

   or to use the (async ...) macro."
  [request]
  (let [body (:body request)]
    (cond
      (instance? InputStream body)
      (run-pipeline request)
      
      (or (nil? body) (and (sequential? body) (empty? body)))
      (run-pipeline request)
      
      (to-channel-buffer? body)
      (run-pipeline
	(assoc request :body (-> body to-channel-buffer channel-buffer->input-stream)))

      (channel? body)
      (run-pipeline (reduce* concat [] body)
	#(assoc request :body (-> % byte-buffers->channel-buffer channel-buffer->input-stream)))

      :else
      (run-pipeline request))))

(defn wrap-ring-handler
  "Wraps a synchronous Ring handler, such that it can be used in start-http-server.  If certain
   routes within the application are asynchronous, wrap those handler functions in
   wrap-aleph-handler."
  [f]
  (fn [channel request]
    (run-pipeline (request-body->input-stream request)
      (fn [request]
	(let [response (f (assoc request :channel channel))]
	  (when (and
		  response
		  (not (:websocket request))
		  (not (::ignore response))
		  (not (result-channel? response)))
	    (enqueue channel response)))))))

