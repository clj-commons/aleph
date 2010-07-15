;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.pipeline.channel
  (:use
    [clojure.contrib.def :only (defmacro- defvar)]
    [aleph.pipeline.future])
  (:import
    [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defprotocol Channel
  (receive [channel] [channel timeout])
  (write [channel msg])
  (close [channel]))

(def delayed-executor (ScheduledThreadPoolExecutor. 1))

(defn delay-fn [f delay]
  (.schedule delayed-executor ^{:type Runnable} f (long delay) TimeUnit/MILLISECONDS))

(defn channel []
  (let [messages (ref [])
	listeners (ref #{})
	closed? (ref false)]
    (reify
      Channel
      (receive [channel]
	(receive channel 0))
      (receive [channel timeout]
	(if-let [msg (dosync
		       (when-not (empty? @messages)
			 (let [msg (first messages)]
			   (alter messages rest)
			   msg)))]
	  (immediate-success msg)
	  (do
	    (let [ftr (evented-future)]
	      (when (pos? timeout)
		(delay-fn
		  #(when (dosync
			   (when (@listeners ftr)
			     (alter listeners disj ftr)
			     true))
		     (error! ftr nil :timeout))
		  0))
	      (dosync
		(alter listeners conj ftr)
		ftr)))))
      (write [channel msg]
	(when-let [coll (dosync
			  (if (and (empty? @messages) (not (empty? @listeners)))
			    (let [coll @listeners]
			      (ref-set listeners nil)
			      coll)
			    (do
			      (alter messages conj msg)
			      nil)))]
	  (doseq [evt coll]
	    (success! evt msg)))))))
