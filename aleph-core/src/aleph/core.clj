;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:author "Zachary Tellman"
    :doc "The core data structures for Aleph."}
  aleph.core
  (:use
    [potemkin])
  (:require
    [aleph.core.pipeline :as pipeline]
    [aleph.core.channel :as channel])) 

;;;

(import-fn pipeline/pipeline)
(import-fn pipeline/blocking)
(import-fn pipeline/read-channel)
(import-fn pipeline/read-merge)
(import-fn pipeline/redirect)
(import-fn pipeline/restart)
(import-fn pipeline/complete)
(import-fn pipeline/run-pipeline)
(import-fn pipeline/on-success)
(import-fn pipeline/on-error)
(import-fn pipeline/wait-for-pipeline)

(import-fn #'channel/receive)
(import-fn #'channel/receive-all)
(import-fn #'channel/cancel-callback)
(import-fn #'channel/enqueue)
(import-fn #'channel/enqueue-and-close)
(import-fn #'channel/sealed?)
(import-fn #'channel/closed?)
(import-fn channel/channel?)
(import-fn channel/poll)
(import-fn channel/channel)
(import-fn channel/channel-pair)
(import-fn channel/constant-channel)
(import-fn channel/finite-channel)
(import-fn channel/channel-seq)
(import-fn channel/lazy-channel-seq)
(import-fn channel/wait-for-message)
(import-fn channel/siphon)
(import-fn channel/siphon-when)
(import-fn channel/wrap-channel)
(import-fn channel/wrap-endpoint)
(import-fn channel/named-channel)
(import-fn channel/release-named-channel)

(defn receive-in-order
  "Consumes messages from a channel one at a time.  The callback will only
   receive the next message once it has completed processing the previous one."
  [ch f]
  (if (closed? ch)
    (pipeline/pipeline-channel
      (constant-channel nil)
      channel/nil-channel)
    (run-pipeline ch
      read-channel
      (fn [msg]
	(f msg)
	(when-not (closed? ch)
	  (restart))))))
