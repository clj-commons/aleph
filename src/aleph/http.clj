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
    [lamina.core]
    [potemkin])
  (:require
    [aleph.http.server :as server]
    [aleph.http.client :as client]
    [aleph.http.utils :as utils]
    [aleph.http.policy-file :as policy]))

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

(defn wrap-ring-handler
  "Wraps a synchronous Ring handler, such that it can be used in start-http-server.  If certain
   routes within the application are asynchronous, wrap those handler functions in
   wrap-aleph-handler."
  [f]
  (fn [channel request]
    (let [response (f (assoc request :channel channel))]
      (when (and
	      response
	      (not (:websocket request))
	      (not (::ignore response)))
	(enqueue channel response)))))

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
  ([request]
     (request-params request nil))
  ([request options]
     (merge
       (utils/body-params request options)
       (utils/query-params request options))))

(defn request-cookie
  [request]
  (utils/cookie->hash (get-in request [:headers "cookie"])))

(defn request-client-info
  [request]
  (utils/parse-user-agent (get-in request [:headers "user-agent"])))

