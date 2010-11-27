;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.utils
  (:use [lamina core]))

(defn request-handler [ch]
  (let [requests (channel)]
    (run-pipeline requests
      read-channel
      (fn [[request handler]]
	(enqueue ch request)
	(run-pipeline ch
	  :error-handler (fn [_ _]
			   (enqueue handler nil)
			   (complete nil))
	  read-channel
	  #(enqueue handler %)))
      (fn [_] (restart)))
    (fn []
      (let [request-channel (constant-channel)
	    response-channel (constant-channel)]
	(receive request-channel #(enqueue requests [% response-channel]))
	(splice response-channel request-channel)))))

(defn pipelined-request-handler [ch]
  (let [response-handlers (channel)]
    (run-pipeline response-handlers
      read-channel
      (fn [handler]
	(run-pipeline ch
	  :error-handler (fn [_ _]
			   (enqueue handler nil)
			   (complete nil))
	  read-channel
	  #(enqueue handler %)))
      (fn [_] (restart)))
    (fn []
      (let [request-channel (constant-channel)
	    response-channel (constant-channel)]
	(dosync
	  (siphon request-channel ch)
	  (enqueue response-handlers response-channel))
	(splice response-channel request-channel)))))



