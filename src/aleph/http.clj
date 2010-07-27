;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.http
  (:require
    [aleph.core :as core]
    [aleph.http.server :as server]
    [aleph.http.client :as client]))

(defn start-http-server
  "Starts an HTTP server."
  [handler options]
  (core/start-server
    #(server/create-pipeline handler options)
    (assoc options
      :error-handler (fn [^Throwable e] (.printStackTrace e)))))

(defn http-client
  "Create an HTTP client."
  [options]
  (core/client
    #(client/create-pipeline % options)
    #(client/transform-request
       "http"
       (:host options)
       (:port options)
       %)
    options))
