;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.core.pipeline
  (:use
    [clojure.contrib.def :only (defmacro- defvar)]
    [aleph.core.channel]
    [clojure.pprint])
  (:import
    [java.util.concurrent
     TimeoutException]))

;;;

(defvar *context* nil)

(defn- outer-result []
  (:outer-result *context*))

(defn- inner-error-handler []
  (when-not (= (:inner-error-handler *context*) (:outer-error-handler *context*))
    (:inner-error-handler *context*)))

(defn- outer-error-handler []
  (:outer-error-handler *context*))

(defn- current-pipeline []
  (:pipeline *context*))

(defn- initial-value []
  (:initial-value *context*))

(defmacro- with-context [context & body]
  `(binding [*context* ~context]
     ~@body))

(defn- tag= [x tag]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= tag))))

(defn pipeline? [x]
  (tag= x ::pipeline))

(defn redirect? [x]
  (tag= x ::redirect))

(defn pipeline-channel? [x]
  (tag= x ::pipeline-channel))

(defn pipeline-channel
  ([]
     (pipeline-channel
       (constant-channel)
       (constant-channel)))
  ([success-channel error-channel]
     ^{:tag ::pipeline-channel
       :type ::pipeline-channel}
     {:success success-channel
      :error error-channel}))

;;;

(declare handle-result)

(defn- poll-pipeline-channel [chs fns context]
  (receive (poll* chs -1)
    (fn [[typ result]]
      (with-context context
	(case typ
	  :success
	  (handle-result result fns context)
	  :error
	  (let [possible-redirect (when (inner-error-handler)
				    (apply (inner-error-handler) result))
		possible-redirect (if (or (redirect? possible-redirect) (not (outer-error-handler)))
				    possible-redirect
				    (apply (outer-error-handler) result))]
	    (if (redirect? possible-redirect)
	      (handle-result
		(:value possible-redirect)
		(-> possible-redirect :pipeline :stages)
		(assoc context
		  :inner-error-handler (-> possible-redirect :pipeline :error-handler)
		  :initial-value (:value possible-redirect)
		  :pipeline (-> possible-redirect :pipeline)))
	      (enqueue (-> context :outer-result :error) result))))))))

(defn- handle-result [result fns context]
  (with-context context
    (cond
      (redirect? result)
      (recur
	(:value result)
	(-> result :pipeline :stages)
	(assoc context
	  :pipeline (:pipeline result)
	  :initial-value (:value result)
	  :inner-error-handler (-> result :pipeline :error-handler)))
      (pipeline-channel? result)
      (poll-pipeline-channel result fns context)
      :else
      (let [{outer-success :success outer-error :error} (outer-result)]
	(if (empty? fns)
	  (enqueue outer-success result)
	  (let [f (first fns)]
	    (if (pipeline? f)
	      (poll-pipeline-channel (f result) (next fns) context)
	      (try
		(recur (f result) (next fns) context)
		(catch Exception e
		  (let [failure (pipeline-channel)]
		    (enqueue (:error failure) [result e])
		    (poll-pipeline-channel failure fns context)))))))))))

;;;

(defn- get-opts [opts+rest]
  (if (-> opts+rest first keyword?)
    (concat (take 2 opts+rest) (get-opts (drop 2 opts+rest)))
    nil))

(defn pipeline
  "Returns a function with an arity of one.  Invoking the function will return
   a pipeline channel.

   Stages should either be pipelines, or functions with an arity of one.  These functions
   should either return a pipeline channel, a redirect signal, or a value which will be passed
   into the next stage."
  [& opts+stages]
  (let [opts (apply hash-map (get-opts opts+stages))
	stages (drop (* 2 (count opts)) opts+stages)
	pipeline {:stages stages
		  :error-handler (:error-handler opts)}]
    ^{:tag ::pipeline
      :pipeline pipeline}
    (fn [x]
      (let [ch (pipeline-channel)]
	(handle-result
	  x
	  (:stages pipeline)
	  {:outer-error-handler (:error-handler pipeline)
	   :pipeline pipeline
	   :outer-result ch
	   :initial-value x})
	ch))))

(defn redirect
  "When returned from a pipeline stage, redirects the execution flow.."
  ([pipeline val]
     (when-not (pipeline? pipeline)
       (throw (Exception. "First parameter must be a pipeline.")))
     ^{:tag ::redirect}
     {:pipeline (-> pipeline meta :pipeline)
      :value val}))

(defn restart
  "Redirects to the beginning of the current pipeline.  If no value is passed in, defaults
   to the value previously passed into the pipeline."
  ([]
     (restart (initial-value)))
  ([val]
     ^{:tag ::redirect}
     {:pipeline (current-pipeline)
      :value val}))

(defn complete
  "Short-circuits the pipeline, and passes the result to the outermost pipeline channel."
  [result]
  (redirect
    (pipeline
      (fn [_]
	(pipeline-channel
	  (constant-channel result)
	  nil-channel)))
    nil))

(defn run-pipeline
  "Equivalent to ((pipeline opts+stages) initial-value).

   Returns a pipeline future."
  [initial-value & opts+stages]
  ((apply pipeline opts+stages) initial-value))

(defn blocking
  "Takes a synchronous function, and returns a function which will be executed asynchronously,
   and whose invocation will return a pipeline channel."
  [f]
  (fn [x]
    (let [result (pipeline-channel)
	  {data :success error :error} result
	  context *context*]
      (future
	(with-context context
	  (try
	    (enqueue data (f x))
	    (catch Exception e
	      (enqueue error [x e])))))
      result)))

(defn read-channel
  "For reading channels within pipelines.  Takes a simple channel, and returns
   a pipeline channel."
  ([ch]
     (read-channel ch -1))
  ([ch timeout]
     (let [result (pipeline-channel)
	   {success :success error :error} result]
       (receive
	 (poll {:ch ch} timeout)
	 #(if %
	    (enqueue success (second %))
	    (enqueue error [nil (TimeoutException. (str "read-channel timed out after " timeout " ms"))])))
       result)))

(defn read-merge
  "For merging asynchronous reads into a pipeline.

   'read-fn' is a function that takes no parameters and returns a value, which
   can be a pipeline channel representing an asynchronous read.

   'merge-fn' is a function which takes two parameters - the incoming value from
   the pipeline and the value from read-fn - and returns a single value that
   will propagate forward into the pipeline."
  [read-fn merge-fn]
  (fn [input]
    (run-pipeline (read-fn)
      #(merge-fn input %))))

;;;

(defn on-success
  "Adds a callback to a pipeline channel which will be called if the pipeline succeeds.

   The function will be called with (f result)"
  [ch f]
  (receive (:success ch) f))

(defn on-error
  "Adds a callback to a pipeline channel which will be called if the
   pipeline terminates due to an error.

   The function will be called with (f intermediate-result exception)."
  [ch f]
  (receive (:error ch) (fn [[result exception]] (f result exception))))

(defn wait-for-pipeline
  "Waits for a pipeline to complete.  If it succeeds, returns the result.
   If there was an error, the exception is re-thrown."
  ([pipeline-channel]
     (wait-for-pipeline pipeline-channel -1))
  ([pipeline-channel timeout]
     (let [value (promise)]
       (receive (poll* pipeline-channel timeout)
	 #(deliver value %))
       (let [value @value]
	 (if (nil? value)
	   (throw (TimeoutException. "Timed out waiting for result from pipeline."))
	   (let [[k result] value]
	     (case k
	       :error (throw (second result))
	       :success result)))))))

;;;

(defmethod print-method ::pipeline-channel [ch writer]
  (.write writer
    (str "pipeline-channel\n"
      "  success: "))
  (print-method (:success ch) writer)
  (.write writer "\n  error:   ")
  (print-method (:error ch) writer))
