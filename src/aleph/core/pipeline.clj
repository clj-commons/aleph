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
    [aleph.core.channel])
  (:import
    [org.jboss.netty.channel
     ChannelFuture
     ChannelFutureListener]
    [java.util.concurrent
     TimeoutException]))

;;;

(defvar *context* nil)

(defn- outer-result []
  (:outer-result *context*))

(defn- pipeline-error-handler []
  (:error-handler *context*))

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
  (receive (poll chs -1)
    (fn [[typ result]]
      (case typ
	:success
	(handle-result result fns context)
	:error
	(let [outer-error (-> context :outer-result :error)]
	  (if-not (pipeline-error-handler)
	    (enqueue outer-error result)
	    (let [possible-redirect (apply (pipeline-error-handler) result)]
	      (if (redirect? possible-redirect)
		(handle-result
		  (:value possible-redirect)
		  (-> possible-redirect :pipeline :stages)
		  (assoc context
		    :error-handler (-> possible-redirect :pipeline :error-handler)
		    :initial-value (:value possible-redirect)
		    :pipeline (-> possible-redirect :pipeline)))
		(enqueue outer-error result)))))))))

(defn- handle-result [result fns context]
  ;;(println "handle-result" result)
  (with-context context
    (cond
      (redirect? result)
      (recur
	(:value result)
	(-> result :pipeline :stages)
	(assoc context
	  :pipeline (:pipeline result)
	  :initial-value (:value result)
	  :error-handler (-> result :pipeline :error-handler)))
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
		  ;;(.printStackTrace e)
		  (let [failure (pipeline-channel)]
		    (enqueue (:error failure) [result e])
		    (poll-pipeline-channel failure fns context)))))))))))

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
	  {:error-handler (:error-handler pipeline)
	   :pipeline pipeline
	   :outer-result ch
	   :initial-value x})
	ch))))

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

(def nil-channel
  (reify AlephChannel
    (listen [_ _])
    (receive [_ _])))

(defn receive-from-channel
  "Creates a pipeline stage which takes a basic channel as a parameter,
   and returns a single message from that channel."
  [ch]
  (pipeline-channel ch nil-channel))    

;;;

(defn on-success
  "Adds a callback to a pipeline channel which will be called if the
   pipeline succeeds.

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

(defn wrap-netty-future
  "Creates a pipeline stage that takes a Netty ChannelFuture, and returns
   a Netty Channel."
  [^ChannelFuture netty-future]
  (let [ch (pipeline-channel)]
    (.addListener netty-future
      (reify ChannelFutureListener
	(operationComplete [_ netty-future]
	  (if (.isSuccess netty-future)
	    (enqueue (:success ch) (.getChannel netty-future))
	    (enqueue (:error ch) [nil (.getCause netty-future)]))
	  nil)))
    ch))

;;;

(defmethod print-method ::pipeline-channel [ch writer]
  (.write writer
    (str "pipeline-channel\n"
      "  success: "))
  (print-method (:success ch) writer)
  (.write writer "\n  error:   ")
  (print-method (:error ch) writer))
