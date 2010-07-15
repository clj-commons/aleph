;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.pipeline.future
  (:use
    [clojure.contrib.def :only (defmacro-)])
  (:import
    [org.jboss.netty.channel
     ChannelFuture
     ChannelFutureListener]))

(defprotocol EventedFuture
  (add-listener [future callback]
    "Adds a listener.")
  (remove-listener [future callback]
    "Removes a callback.")
  (success? [future]
    "Returns true if the evented-future has completed successfully.")
  (complete? [future]
    "Returns true if the evented-future is complete.")
  (cause [future]
    "Returns the exception that caused the evented-future to fail.")
  (result [future]
    "Returns the result of the evented-future, or nil if it did not complete successfully."))

(defprotocol EventedFutureTrigger
  (complete! [future result exception]))

(defn success! [ftr result]
  (complete! ftr result nil))

(defn error! [ftr result exception]
  (complete! ftr result exception))

(defn evented-future? [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::future))))

(defn- immediate-future [success? result exception]
  ^{:tag ::future}
  (reify
    EventedFuture
    (complete? [_] true)
    (success? [_] success?)
    (add-listener [this f] (f this))
    (remove-listener [_ _])
    (cause [_] exception)
    (result [_] result)))

(defn immediate-success [result]
  (immediate-future true result nil))

(defn immediate-failure [result exception]
  (immediate-future false result exception))

(defn evented-future
  "Returns a future which can be triggered via error! or success!"
  []
  (let [complete-val (ref false)
	result-val (ref nil)
	cause-val (ref nil)
	listeners (ref #{})]
    ^{:tag ::future}
    (reify

      Object
      (toString [this]
	(cond
	  (success? this) (str "complete: [ " (result this) " ]")
	  (complete? this) (str "error: [ " (cause this) " " (result this) " ]")
	  :else "pending..."))
      
      EventedFuture
      (complete? [this]
	@complete-val)
      (success? [this]
	(dosync
	  (and (complete? this) (not (cause this)))))
      (add-listener [this f]
	(when (dosync
		(if (complete? this)
		  true
		  (do
		    (alter listeners conj f)
		    false)))
	  (f this))
	nil)
      (remove-listener [this f]
	(dosync (alter listeners disj f)))
      (cause [_]
	@cause-val)
      (result [this]
	(when (complete? this)
	  @result-val))

      EventedFutureTrigger
      (complete! [this result exception]
	(doseq [l (dosync
		    (when (complete? this)
		      (throw (Exception. "An future can only be triggered once.")))
		    (ref-set complete-val true)
		    (ref-set cause-val exception)
		    (ref-set result-val result)
		    (let [coll @listeners]
		      (ref-set listeners nil)
		      coll))]
	  (try
	    (l this)
	    (catch Exception e
	      (.printStackTrace e))))))))





