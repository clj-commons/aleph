;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:author "Zachary Tellman"
    :doc "An asynchronous server."}
  aleph.core
  (:use
    [clojure.contrib.def :only (defmacro-)])
  (:require
    [aleph.netty :as netty]
    [aleph.core.pipeline :as pipeline]
    [aleph.core.channel :as channel])) 

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
(import-fn pipeline/receive-in-order)
(import-fn pipeline/receive-from-channel)
(import-fn pipeline/redirect)
(import-fn pipeline/restart)
(import-fn pipeline/run-pipeline)
(import-fn pipeline/on-success)
(import-fn pipeline/on-error)
(import-fn pipeline/wait-for-pipeline)

(import-fn #'channel/receive)
(import-fn #'channel/receive-all)
(import-fn #'channel/cancel-callback)
(import-fn #'channel/enqueue)
(import-fn #'channel/enqueue-and-close)
(import-fn #'channel/closed?)
(import-fn channel/poll)
(import-fn channel/channel-pair)
(import-fn channel/channel)
(import-fn channel/constant-channel)
(import-fn channel/channel-seq)
(import-fn channel/wait-for-message)


;;;

(defn stop-server
  "Stops a server."
  [stop-fn]
  (stop-fn))

