;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.


(ns
  ^{:skip-wiki true}
  aleph.pipeline
  (:use
    [clojure.contrib.def :only (defmacro- defvar)])
  (:import
    [org.jboss.netty.channel
     ChannelFuture
     ChannelFutureListener]))

(set! *warn-on-reflection* true)

(defprotocol CancellablePipelineFuture
  (cancel [future])
  (on-cancel [future callback])
  (cancelled? [future]))

(defprotocol PipelineFuture
  (on-success [future callback]
    "Enqueues the callback to be invoked when the pipeline-future completes successfully.")
  (success? [future]
    "Returns true if the pipeline-future has completed successfully.")
  (on-completion [future callback]
    "Enqueues the callback to be invoked when the pipeline-future completes.")
  (complete? [future]
    "Returns true if the pipeline-future is complete.")
  (on-error [future callback]
    "Enqueues the callback to be invoked when the pipeline-future completes unsucccessfully.")
  (error? [future]
    "Returns true if the pipeline-future did not complete successfuly.")
  (cause [future]
    "Returns the exception that caused the pipeline-future to fail.")
  (result [future]
    "Returns the result of the pipeline-future, or nil if it did not complete successfully."))

(defprotocol PipelineFutureProxy
  (success! [future result]
    "Triggers the successful conclusion of a future-proxy.")
  (error! [future error result]
    "Triggers the unsuccessful conclusion of a future-proxy."))

(defmacro- add-channel-listener [ftr pred & body]
  `(.addListener ~ftr
     (if ~pred
       (do
	 ~@body)
       (reify ChannelFutureListener
	(operationComplete [_ _]
	  (when ~pred
	    ~@body))))))

(defn wrap-channel-future [^ChannelFuture ftr result]
  ^{:tag ::future}
  (reify

    PipelineFuture
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
    (on-error [this f]
      (add-channel-listener ftr
	(error? this)
	(f this)))
    (error? [_]
      (.getCause ftr))
    (result [_] result)

    CancellablePipelineFuture
    (cancelled? [_]
      (.isCancelled ftr))
    (on-cancel [this f]
      (add-channel-listener ftr
	(cancelled? this)
	(f this)))
    (cancel [_]
      (.cancel ftr))))

;;;

(defn pipeline-future? [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::future))))

(defn immediate-success
  "Returns an future which is immediately successful."
  [result]
  ^{:tag ::future}
  (reify
    PipelineFuture
    (complete? [_] true)
    (success? [_] true)
    (on-completion [this f] (f this))
    (on-success [this f] (f this))
    (on-error [_ _])
    (error? [_] false)
    (cause [_] nil)
    (result [_] result)))

(defn immediate-failure
  "Returns a future which is immediately unsuccessful."
  [exception result]
  ^{:tag ::future}
  (reify
    PipelineFuture
    (complete? [_] true)
    (success? [_] false)
    (on-completion [this f] (f this))
    (on-success [this f] nil)
    (on-error [this f] (f this))
    (error? [_] true)
    (cause [_] exception)
    (result [_] result)))

(defn future-proxy
  "Returns a future which can be triggered via error! or success!"
  []
  (let [complete-val (ref false)
	result-val (ref nil)
	cause-val (ref nil)
	listeners (ref {})
	publish-fn
	(fn [ftr rslt typ error]
	  (let [coll (dosync
		       (when (complete? ftr)
			 (throw (Exception. "An future can only be triggered once.")))
		       (ref-set complete-val true)
		       (ref-set cause-val error)
		       (ref-set result-val rslt)
		       (let [coll (@listeners typ)]
			 (ref-set listeners nil)
			 coll))]
	    (doseq [l coll]
	      (try
		(l ftr)
		(catch Exception e
		  (.printStackTrace e))))))]
    ^{:tag ::future}
    (reify

      Object
      (toString [this]
	(cond
	  (error? this) (str "ERROR: " (cause this) " " (result this))
	  (complete? this) (str "complete: " (result this))
	  :else (str "pending...")))
      
      PipelineFuture
      (complete? [this]
	@complete-val)
      (success? [this]
	(and (complete? this) (not (error? this))))
      (on-success [this f]
	(when (dosync
		(if (complete? this)
		  (not (error? this))
		  (do
		    (alter listeners update-in [:success] #(conj % f))
		    false)))
	  (f this))
	nil)
      (on-completion [this f]
	(on-success this f)
	(on-error this f))
      (on-error [this f]
	(when (dosync
		(if (complete? this)
		  (error? this)
		  (do
		    (alter listeners update-in [:error] #(conj % f))
		    false)))
	  (f this))
	nil)
      (error? [this]
	(not (nil? (cause this))))
      (cause [_]
	@cause-val)
      (result [this]
	(when (complete? this)
	  @result-val))

      PipelineFutureProxy
      (success! [this x]
	(publish-fn this x :success nil))
      
      (error! [this exception x]
	(publish-fn this x :error exception)))))

;;;

(defvar *context*)

(defn- outer-future []
  (:outer-future *context*))

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
  "When returned from a pipeline stage, redirects the execution flow.."
  [pipeline val]
  (when-not (pipeline? pipeline)
    (throw (Exception. "First parameter must be a pipeline.")))
  ^{:tag ::redirect}
  {:pipeline (-> pipeline meta :pipeline)
   :value val})

(defn restart
  "Redirects to the beginning of the current pipeline.  If no value is passed in, defaults
   to the value previously passed into the pipeline."
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

(declare handle-future-result)

(defn- wait-for-future
  [ftr fns context]
  ;;(println "wait for future" ftr "\n")
  (on-error ftr
    (fn [ftr]
      (with-context context
	(if-not (pipeline-error-handler)
	  ;;Halt pipeline with error if there's no error-handler
	  (error! (outer-future) (cause ftr) (result ftr))
	  (let [result ((pipeline-error-handler) (cause ftr) (result ftr))]
	    (if (redirect? result)
	      ;;If error-handler issues a redirect, go there
	      (handle-future-result
		(:value result)
		(-> result :pipeline :stages)
		(assoc context
		  :error-handler (-> result :pipeline :error-handler)
		  :pipeline (:pipeline result)))
	      ;;Otherwise, halt pipeline
	      (error! (outer-future) (cause ftr) result)))))))
  (on-success ftr
    (fn [ftr]
      (handle-future-result (result ftr) fns context))))

(defn- handle-future-result
  [val fns context]
  ;;(println "handle-future-result" val (pipeline-future? val) "\n")
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
	(pipeline-future? val)
	(wait-for-future val fns context)
	:else
	(if (empty? fns)
	  (success! (outer-future) val)
	  (let [f (first fns)]
	    (if (pipeline? f)
	      (wait-for-future (f val) (rest fns) context)
	      (try
		(recur (f val) (rest fns) context)
		(catch Exception e
		  (wait-for-future (immediate-failure e val) fns context)))))))
      (catch Exception e
	(.printStackTrace e)))))

(defn- get-specs [specs+rest]
  (if (-> specs+rest first keyword?)
    (concat (take 2 specs+rest) (get-specs (drop 2 specs+rest)))
    nil))

(defn pipeline
  "Returns a function with an arity of one.  Invoking the function will return
   a pipeline-future.

   Stages should either be pipelines, or functions with an arity of one.  These functions
   should either return a pipeline-future, a redirect signal, or a value which will be passed
   into the next stage."
  [& opts+stages]
  (let [opts (apply hash-map (get-specs opts+stages))
	stages (drop (* 2 (count opts)) opts+stages)
	pipeline {:stages stages
		  :error-handler (:error-handler opts)}]
    ^{:tag ::pipeline
      :pipeline pipeline}
    (fn [x]
      (let [outer-future (future-proxy)]
	(handle-future-result
	  x
	  (:stages pipeline)
	  {:error-handler (:error-handler pipeline)
	   :pipeline pipeline
	   :outer-future outer-future
	   :initial-value x})
	outer-future))))

(defn blocking
  "Takes a synchronous function, and returns a function which will be executed asynchronously,
   and whose invocation will return a pipeline-future."
  [f]
  (fn [x]
    (let [ftr (future-proxy)
	  context *context*]
      (future
	(with-context context
	  (try
	    (let [result (f x)]
	      (success! ftr result))
	    (catch Exception e
	      (error! ftr e x)))))
      ftr)))
