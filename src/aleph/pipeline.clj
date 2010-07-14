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
    [clojure.contrib.def :only (defmacro- defvar)]
    [aleph.pipeline.future]))

(set! *warn-on-reflection* true)

;;;

(defvar *context* nil)

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

(defn redirect?
  [x]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= ::redirect))))

(declare handle-future-result)

(defn- wait-for-future
  [ftr fns context]
  ;;(println "wait for future" ftr "\n")
  (add-listener ftr
    (fn [ftr]
      (with-context context
	(if (success? ftr)
	  (handle-future-result (result ftr) fns context)
	  (if-not (pipeline-error-handler)
	    ;;Halt pipeline with error if there's no error-handler
	    (error! (outer-future) (result ftr) (cause ftr))
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
		(error! (outer-future) result (cause result))))))))))

(defn- handle-future-result
  [val fns context]
  ;;(println "handle-future-result" "\n")
  (with-context context
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
		(wait-for-future (immediate-failure val e) fns context)))))))))

;;;

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

;;;

(defn- get-opts [opts+rest]
  (if (-> opts+rest first keyword?)
    (concat (take 2 opts+rest) (get-opts (drop 2 opts+rest)))
    nil))

(defn pipeline
  "Returns a function with an arity of one.  Invoking the function will return
   a pipeline-future.

   Stages should either be pipelines, or functions with an arity of one.  These functions
   should either return a pipeline-future, a redirect signal, or a value which will be passed
   into the next stage."
  [& opts+stages]
  (let [opts (apply hash-map (get-opts opts+stages))
	stages (drop (* 2 (count opts)) opts+stages)
	pipeline {:stages stages
		  :error-handler (:error-handler opts)}]
    ^{:tag ::pipeline
      :pipeline pipeline}
    (fn [x]
      (let [ftr (pipeline-future)]
	(handle-future-result
	  x
	  (:stages pipeline)
	  {:error-handler (:error-handler pipeline)
	   :pipeline pipeline
	   :outer-future ftr
	   :initial-value x})
	ftr))))

(defn blocking
  "Takes a synchronous function, and returns a function which will be executed asynchronously,
   and whose invocation will return a pipeline-future."
  [f]
  (fn [x]
    (let [ftr (pipeline-future)
	  context *context*]
      (future
	(with-context context
	  (try
	    (success! ftr (f x))
	    (catch Exception e
	      (error! ftr x e)))))
      ftr)))
