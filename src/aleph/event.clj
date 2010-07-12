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

(set! *warn-on-reflection* true)

(defprotocol CancellableEvent
  (cancel [event])
  (on-cancel [event callback])
  (cancelled? [event]))

(defprotocol FailableEvent
  (on-error [event callback])
  (error? [event])
  (cause [event]))

(defprotocol Event
  (state [event])
  (on-success [event callback])
  (success? [event])
  (on-completion [event callback])
  (complete? [event]))

(defprotocol EventTriggers
  (success! [event state])
  (error! [event error state]))

(defmacro- add-channel-listener [ftr pred & body]
  `(.addListener ~ftr
     (if ~pred
       (do
	 ~@body)
       (reify ChannelFutureListener
	(operationComplete [_ _]
	  (when ~pred
	    ~@body))))))

(defn wrap-channel-event [^ChannelFuture ftr state]
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
    (state [this] state)

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

(defn immediate-success
  "Returns an event which is immediately successful, and contains 'state'."
  [state]
  ^{:tag ::event}
  (reify
    Event
    (complete? [_] true)
    (success? [_] true)
    (on-completion [this f] (f this))
    (on-success [this f] (f this))
    (state [_] state)))

(defn immediate-failure
  [exception state]
  ^{:tag ::event}
  (reify
    Event
    (complete? [_] true)
    (success? [_] false)
    (on-completion [this f] (f this))
    (on-success [this f] nil)
    (state [_] state)
    FailableEvent
    (on-error [this f] (f this))
    (error? [_] true)
    (cause [_] exception)))

(defn create-event
  "Returns an event which can be triggered via error! or success!"
  []
  (let [complete? (ref false)
	state (ref nil)
	cause (ref nil)
	listeners (ref {})
	publish-fn
	(fn [evt rslt typ error]
	  (when @complete?
	    (throw (Exception. "An event can only be triggered once.")))
	  (let [coll (dosync
		       (ref-set complete? true)
		       (ref-set cause error)
		       (ref-set state rslt)
		       (let [coll (@listeners typ)]
			 (ref-set listeners nil)
			 coll))]
	    (doseq [l coll]
	      (try
		(l evt)
		(catch Exception e
		  (.printStackTrace e))))))]
    ^{:tag ::event}
    (reify
      Event
      (complete? [this]
	@complete?)
      (success? [this]
	(and @complete? (not (error? this))))
      (on-success [this f]
	(when (dosync
		(if @complete?
		  (not (error? this))
		  (do
		    (alter listeners update-in [:success] #(conj % f))
		    false)))
	  (f this))
	nil)
      (on-completion [this f]
	(on-success this f)
	(on-error this f))
      (state [_]
	@state)

      FailableEvent
      (on-error [this f]
	(when (dosync
		(if @complete?
		  (error? this)
		  (do
		    (alter listeners update-in [:error] #(conj % f))
		    false)))
	  (f this))
	nil)
      (error? [_]
	(not (nil? @cause)))
      (cause [_]
	@cause)

      EventTriggers
      (success! [this x]
	(publish-fn this x :success nil))
      
      (error! [this exception x]
	(publish-fn this x :error exception)))))

;;;

(defvar *context*)

(defn- outer-event []
  (:outer-event *context*))

(defn- pipeline-error-handler []
  (:error-handler *context*))

(defn- current-pipeline []
  (:pipeline *context*))

(defn- initial-value []
  (:initial-value *context*))

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
  ([]
     (restart (initial-value)))
  ([val]
     ^{:tag ::redirect}
     {:pipeline (current-pipeline)
      :value val}))

(defn redirect?
  [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::redirect))))

(declare handle-event-result)

(defn- wait-for-event
  [evt fns context]
  ;;(println "wait for event" "\n")
  (on-error evt
    (fn [evt]
      (with-context context
	(if-not (pipeline-error-handler)
	  ;;Halt pipeline with error if there's no error-handler
	  (error! (outer-event) (cause evt) (state evt))
	  (let [state ((pipeline-error-handler) (cause evt) (state evt))]
	    (if (redirect? state)
	      ;;If error-handler issues a redirect, go there
	      (handle-event-result
		(:value state)
		(-> state :pipeline :stages)
		(assoc context
		  :error-handler (-> state :pipeline :error-handler)
		  :pipeline (:pipeline state)))
	      ;;Otherwise, halt pipeline
	      (error! (outer-event) (cause evt) state)))))))
  (on-success evt
    (fn [evt]
      (handle-event-result (state evt) fns context))))

(defn- handle-event-result
  [val fns context]
  ;;(println "handle-event-result" val (event? val) "\n")
  (with-context context
    (try
      (cond
	(redirect? val)
	(recur
	  (:value val)
	  (-> val :pipeline :stages)
	  (assoc context
	    :pipeline (:pipeline val)
	    :initial-value (:value val)
	    :error-handler (-> val :pipeline :error-handler)))
	(event? val)
	(wait-for-event val fns context)
	:else
	(do
	  (if (empty? fns)
	   (success! (outer-event) val)
	   (let [f (first fns)]
	     (if (pipeline? f)
	       (wait-for-event (f val) (rest fns) context)
	       (try
		 (recur (f val) (rest fns) context)
		 (catch Exception e
		   (wait-for-event (immediate-failure e val) fns context))))))))
      (catch Exception e
	(.printStackTrace e)))))

(defn- get-specs [specs+rest]
  (if (-> specs+rest first keyword?)
    (concat (take 2 specs+rest) (get-specs (drop 2 specs+rest)))
    nil))

(defn pipeline
  [& opts+stages]
  (let [opts (apply hash-map (get-specs opts+stages))
	stages (drop (* 2 (count opts)) opts+stages)
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
	   :pipeline pipeline
	   :outer-event outer-evt
	   :initial-value x})
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
	      (error! evt e x)))))
      evt)))
