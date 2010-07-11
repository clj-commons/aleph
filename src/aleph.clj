;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph
  (:require
    [aleph.http :as http]
    [aleph.core :as core]))

(defn run-server [handler options]
  (let [port (:port options)
	protocol (:protocol options)]
    (core/start-server
      (condp = protocol
	:http #(http/server-pipeline handler options))
      port)))

(defn run-http-server [handler options]
  (run-server handler (assoc options :protocol :http)))

(defn respond!
  [request response]
  (io! ((:respond request) request response)))

(defn stop [server]
  (.close server))
