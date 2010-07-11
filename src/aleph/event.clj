;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.event
  (:use
    [clojure.contrib.def :only (defmacro-)])
  (:import
    [org.jboss.netty.channel
     ChannelFuture
     ChannelFutureListener]))

(defprotocol CancellableEvent
  (cancel [event])
  (on-cancel [event callback])
  (cancelled? [event]))

(defprotocol FailableEvent
  (on-error [event callback])
  (error? [event]))

(defprotocol Event
  (result [event])
  (on-success [event callback])
  (success? [event])
  (on-completion [event callback])
  (complete? [event]))

(defprotocol EventTriggers
  (success! [event result])
  (error! [event error]))

(defmacro- add-channel-listener [ftr pred & body]
  `(.addListener ~ftr
     (if ~pred
       (do
	 ~@body)
       (reify ChannelFutureListener
	(operationComplete [_ _]
	  (when ~pred
	    ~@body))))))

(defn wrap-channel-event [^ChannelFuture ftr]
  ^{:tag ::event}
  (reify

    Event
    (on-completion [this f]
      (add-channel-listener ftr
	(complete? this)
	(f this)))
    (complete? [_]
      (.isDone ftr))
    (on-success [this f]
      (add-channel-listener ftr
	(success? this)
	(f this)))
    (success? [_]
      (.isSuccess ftr))
    (result [this]
      (cond
	(not (complete? this)) :not-complete
	(success? this) :success
	(error? this) (error? this)
	(cancelled? this) :cancelled))

    FailableEvent
    (on-error [this f]
      (add-channel-listener ftr
	(error? this)
	(f this)))
    (error? [_]
      (.getCause ftr))

    CancellableEvent
    (cancelled? [_]
      (.isCancelled ftr))
    (on-cancel [this f]
      (add-channel-listener ftr
	(cancelled? this)
	(f this)))
    (cancel [_]
      (.cancel ftr))))

;;;

(defn event? [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::event))))

(defn immediate-event
  "Returns an event which is immediately successful, and contains 'result'."
  [result]
  ^{:tag ::event}
  (reify
    Event
    (complete? [_] true)
    (success? [_] true)
    (on-completion [this f] (f this))
    (on-success [this f] (f this))
    (result [_] result)))

(defn create-event
  "Returns an event which can be triggered via error! or success!"
  []
  (let [complete? (ref false)
	error? (ref false)
	result (ref nil)
	success-listeners (ref [])
	error-listeners (ref [])
	complete-fn (fn []
		      (dosync
			(ref-set success-listeners nil)
			(ref-set error-listeners nil)
			(ref-set complete? true)))]
    ^{:tag ::event}
    (reify
      Event
      (complete? [_]
	@complete?)
      (success? [_]
	(and @complete? (not @error?)))
      (on-success [this f]
	(if @complete?
	  (when-not @error?
	    (f this))
	  (dosync (alter success-listeners conj f)))
	nil)
      (on-completion [this f]
	(on-success this f)
	(on-error this f))
      (result [_]
	@result)

      FailableEvent
      (on-error [this f]
	(if @complete?
	  (when @error?
	    (f this))
	  (dosync (alter error-listeners conj f)))
	nil)
      (error? [_]
	(and @error?))

      EventTriggers
      (success! [this x]
	(when @complete?
	  (throw (Exception. "An event can only be triggered once.")))
	(doseq
	  [l (dosync
	       (let [listeners @success-listeners]
		 (complete-fn)
		 (ref-set error? false)
		 (ref-set result x)
		 listeners))]
	  (try
	    (l this)
	    (catch Exception e
	      ))))
      
      (error! [this x]
	(when @complete?
	  (throw (Exception. "An event can only be triggered once.")))
	(doseq
	  [l (dosync
	       (let [listeners @error-listeners]
		 (complete-fn)
		 (ref-set error? true)
		 (ref-set result x)
		 listeners))]
	  (try
	    (l this)
	    (catch Exception e
	      )))))))

;;;

(defn redirect
  "When returned from a pipeline stage, redirects the event flow."
  [pipeline val]
  ^{:tag ::redirect}
  {:pipeline pipeline
   :value val})

(defn redirect?
  [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::redirect))))

(declare handle-event-result)

(defn- pipeline-args [pipeline value outer-evt]
  [value
   (:stages pipeline)
   (:error-handler pipeline)
   outer-evt])

(defn- redirect-args [redirect outer-evt]
  (pipeline-args (:pipeline redirect) (:value redirect) outer-evt))

(defn- wait-for-event
  [evt fns error-handler outer-evt]
  (on-error evt
    (fn [evt]
      (if-not error-handler
	(error! outer-evt (result evt))
	(let [result (error-handler (result evt))]
	  (if (redirect? result)
	    (apply handle-event-result (redirect-args redirect outer-evt))
	    (error! outer-evt (result evt)))))))
  (on-success evt
    (fn [evt]
      (handle-event-result (result evt) fns error-handler outer-evt))))

(defn- handle-event-result
  [val fns error-handler outer-evt]
  ;;(println "handle-event-result" val (event? val))
  (cond
    (redirect? val)
    (let [[a b c d] (redirect-args val outer-evt)]
      (recur a b c d))
    (event? val)
    (wait-for-event val fns error-handler outer-evt)
    :else
    (if (empty? fns)
      (success! outer-evt val)
      (recur ((first fns) val) (rest fns) error-handler outer-evt))))

(defn- get-specs [specs+rest]
  (if (-> specs+rest first keyword?)
    (concat (take 2 specs+rest) (get-specs (drop 2 specs+rest)))
    nil))

(defn pipeline
  [& opts+stages]
  (let [opts (apply hash-map (get-specs opts+stages))
	stages (drop (count opts) opts+stages)
	pipeline {:stages stages
		  :error-handler (:error-handler opts)}]
    (fn [x]
      (let [outer-evt (create-event)]
	(apply handle-event-result (pipeline-args pipeline x outer-evt))
	outer-evt))))

(defn blocking [f]
  (fn [x]
    (let [evt (create-event)]
      (future
	(try
	  (let [result (f x)]
	    (success! evt result))
	  (catch Exception e
	    (error! evt e))))
      evt)))
