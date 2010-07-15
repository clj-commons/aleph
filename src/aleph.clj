;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:author "Zachary Tellman"
    :doc "An event-driven server."}
  aleph
  (:use
    [clojure.contrib.def :only (defmacro-)])
  (:require
    [aleph.http :as http]
    [aleph.core :as core]
    [aleph.pipeline :as pipeline]
    [aleph.pipeline.future :as future]
    [aleph.pipeline.channel :as channel]))

(defmacro- import-fn [sym]
  (let [m (meta (eval sym))
        m (meta (intern (:ns m) (:name m)))
        n (:name m)
        arglists (:arglists m)
        doc (:doc m)]
    (list `def (with-meta n {:doc doc :arglists (list 'quote arglists)}) (eval sym))))

;;;

(import-fn pipeline/pipeline)
(import-fn pipeline/blocking)

(import-fn future/evented-future)

(import-fn #'pipeline/redirect)
(import-fn #'pipeline/restart)

(import-fn #'future/success?)
(import-fn #'future/complete?)
(import-fn #'future/cause)
(import-fn #'future/result)
(import-fn future/success!)
(import-fn future/error!)

(defn on-completion [ftr callback]
  (future/add-listener ftr callback))

(defn on-success [ftr callback]
  (future/add-listener ftr #(when (success? %) (callback %))))

(defn on-error [ftr callback]
  (future/add-listener ftr #(when-not (success? %) (callback %))))

;;;

(defn run-server
  "Starts a server."
  [handler options]
  (let [port (:port options)
	protocol (:protocol options)]
    (core/start-server
      (condp = protocol
	:http #(http/server-pipeline handler options))
      port)))

(defn run-http-server
  "Starts an HTTP server."
  [handler options]
  (run-server handler (assoc options :protocol :http)))

(defn respond!
  "Sends a response to the origin of a message."
  [msg response]
  (io! ((:respond msg) msg response)))

(defn stop
  "Stops a server."
  [server]
  (.close server))
