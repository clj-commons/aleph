;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.event
  (:use
    [clojure.contrib.def :only (defmacro- defvar)])
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
	error? (ref nil)
	result (ref nil)
	listeners (ref {})
	publish-fn
	(fn [evt rslt typ error]
	  (when @complete?
	    (throw (Exception. "An event can only be triggered once.")))
	  (doseq [l (dosync
		      (ref-set complete? true)
		      (ref-set error? error)
		      (ref-set result rslt)
		      (let [coll (typ @listeners)]
			(ref-set listeners nil)
			coll))]
	    (try
	      (l evt)
	      (catch Exception e
		(.printStackTrace e)))))]
    ^{:tag ::event}
    (reify
      Event
      (complete? [_]
	@complete?)
      (success? [_]
	(and @complete? (not @error?)))
      (on-success [this f]
	(when (dosync
		(if @complete?
		  (not @error?)
		  (do
		    (alter listeners update-in [:success] #(conj % f))
		    false)))
	  (f this))
	nil)
      (on-completion [this f]
	(on-success this f)
	(on-error this f))
      (result [_]
	@result)

      FailableEvent
      (on-error [this f]
	(when (dosync
		(if @complete?
		  @error?
		  (do
		    (alter listeners update-in [:error] #(conj % f))
		    false)))
	  (f this))
	nil)
      (error? [_]
	(and @error?))

      EventTriggers
      (success! [this x]
	(publish-fn this x :success false))
      
      (error! [this x]
	(publish-fn this x :error true)))))

;;;

(defvar *context*)

(defn- outer-event []
  (:outer-event *context*))

(defn- pipeline-error-handler []
  (:error-handler *context*))

(defn- current-pipeline []
  (:current-pipeline *context*))

(defmacro- with-context [context & body]
  `(binding [*context* ~context]
     ~@body))

(defn pipeline? [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::pipeline))))

(defn redirect
  "When returned from a pipeline stage, redirects the event flow."
  [pipeline val]
  (when-not (pipeline? pipeline)
    (throw (Exception. "First parameter must be a pipeline.")))
  ^{:tag ::redirect}
  {:pipeline (-> pipeline meta :pipeline)
   :value val})

(defn restart
  "Redirects to the beginning of the current pipeline."
  [val]
  ^{:tag ::redirect}
  (current-pipeline))

(defn redirect?
  [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::redirect))))

(declare handle-event-result)

(defn- wait-for-event
  [evt fns context]
  ;;(println "wait for event")
  (on-error evt
    (fn [evt]
      (println "error")
      (with-context context
	(if-not (pipeline-error-handler)
	  ;;Halt pipeline with error if there's no error-handler
	  (error! (outer-event) (result evt))
	  (let [result ((pipeline-error-handler) (result evt))]
	    (if (redirect? result)
	      ;;If error-handler issues a redirect, go there
	      (handle-event-result
		(:value redirect)
		(-> redirect :pipeline :stages)
		(assoc context
		  :error-handler (-> redirect :pipeline :error-handler)
		  :pipeline (:pipeline redirect)))
	      ;;Otherwise, halt pipeline
	      (error! (outer-event) (result evt))))))))
  (on-success evt
    (fn [evt]
      (handle-event-result (result evt) fns context))))

(defn- handle-event-result
  [val fns context]
  ;;(println "handle-event-result" val (event? val) (redirect? val))
  (with-context context
    (try
      (cond
	(redirect? val)
	(recur
	  (:value val)
	  (-> val :pipeline :stages)
	  (assoc context
	    :pipeline (:pipeline redirect)
	    :error-handler (-> redirect :pipeline :error-handler)))
	(event? val)
	(wait-for-event val fns context)
	:else
	(if (empty? fns)
	  (success! (outer-event) val)
	  (let [f (first fns)]
	    (if (pipeline? f)
	      (wait-for-event (f val) (rest fns) context)
	      (recur (f val) (rest fns) context)))))
      (catch Exception e
	(.printStackTrace e)))))

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
    ^{:tag ::pipeline
      :pipeline pipeline}
    (fn [x]
      (let [outer-evt (create-event)]
	(handle-event-result
	  x
	  (:stages pipeline)
	  {:error-handler (:error-handler pipeline)
	   :pipeline :pipeline
	   :outer-event outer-evt})
	outer-evt))))

(defn blocking [f]
  (fn [x]
    (let [evt (create-event)
	  context *context*]
      (future
	(with-context context
	  (try
	    (let [result (f x)]
	      (success! evt result))
	    (catch Exception e
	      (error! evt e)))))
      evt)))
