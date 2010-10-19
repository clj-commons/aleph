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
    [potemkin :only (import-fn)])
  (:require
    [aleph.core.pipeline :as pipeline]
    [aleph.core.channel :as channel]))


;;;; CHANNELS

;; core channel functions
(import-fn #'channel/receive)
(import-fn #'channel/receive-all)
(import-fn #'channel/cancel-callback)
(import-fn #'channel/enqueue)
(import-fn #'channel/enqueue-and-close)
(import-fn #'channel/sealed?)
(import-fn #'channel/closed?)
(import-fn channel/channel?)

;; channel variants
(import-fn channel/splice)
(import-fn channel/channel)
(import-fn channel/channel-pair)
(import-fn channel/constant-channel)
(import-fn channel/sealed-channel)

;; channel utility functions
(import-fn channel/poll)
(import-fn channel/siphon)
(import-fn channel/siphon-while)
(import-fn channel/fork)
(import-fn channel/fork-while)
(import-fn channel/map*)
(import-fn channel/filter*)
(import-fn channel/take*)
(import-fn channel/take-while*)

;; named channels
(import-fn channel/named-channel)
(import-fn channel/release-named-channel)

;; synchronous channel functions
(import-fn channel/lazy-channel-seq)
(import-fn channel/channel-seq)
(import-fn channel/wait-for-message)


;;;; PIPELINES

;; core pipeline functions
(import-fn pipeline/pipeline)
(import-fn pipeline/run-pipeline)

;; pipeline stage helpers
(import-fn pipeline/read-channel)
(import-fn pipeline/read-merge)
(import-fn pipeline/blocking)

;; redirect signals
(import-fn pipeline/redirect)
(import-fn pipeline/restart)
(import-fn pipeline/complete)

;; pipeline result hooks
(import-fn pipeline/on-success)
(import-fn pipeline/on-error)
(import-fn pipeline/wait-for-pipeline)


;;;

(defn receive-in-order
  "Consumes messages from a channel one at a time.  The callback will only receive the next message once
   it has completed processing the previous one.

   This is a lossy iteration over the channel.  Fork the channel is there is another consumer."
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
