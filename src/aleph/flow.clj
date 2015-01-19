(ns aleph.flow
  (:require
    [manifold.deferred :as d])
  (:import
    [io.aleph.dirigiste
     Pool
     Pool$AcquireCallback
     Pool$Controller
     Pool$Generator
     Executors
     Executor
     Executor$Controller
     Pools
     Stats
     Stats$Metric]
    [java.util
     EnumSet]
    [java.util.concurrent
     SynchronousQueue
     ArrayBlockingQueue
     ThreadFactory
     TimeUnit]))

(defn- stats->map
  ([s]
     (stats->map s [0.5 0.9 0.95 0.99 0.999]))
  ([^Stats s quantiles]
     (let [stats (.getMetrics s)
           q #(zipmap quantiles (mapv % quantiles))]
       (merge
         {:num-workers (.getNumWorkers s)}
         (when (contains? stats Stats$Metric/QUEUE_LENGTH)
           {:queue-length (q #(.getQueueLength s %))})
         (when (contains? stats Stats$Metric/QUEUE_LATENCY)
           {:queue-latency (q #(double (/ (.getQueueLatency s %) 1e6)))})
         (when (contains? stats Stats$Metric/TASK_LATENCY)
           {:task-latency (q #(double (/ (.getTaskLatency s %) 1e6)))})
         (when (contains? stats Stats$Metric/TASK_ARRIVAL_RATE)
           {:task-arrival-rate (q #(.getTaskArrivalRate s %))})
         (when (contains? stats Stats$Metric/TASK_COMPLETION_RATE)
           {:task-completion-rate (q #(.getTaskCompletionRate s %))})
         (when (contains? stats Stats$Metric/TASK_REJECTION_RATE)
           {:task-rejection-rate (q #(.getTaskRejectionRate s %))})
         (when (contains? stats Stats$Metric/UTILIZATION)
           {:utilization (q #(.getUtilization s %))})
         ))))

(let [factories (atom 0)]
  (defn- create-thread-factory []
    (let [factory (swap! factories inc)
          threads (atom 0)]
      (reify ThreadFactory
        (newThread [_ r]
          (doto
            (Thread. r (str "aleph-pool-" factory "-" (swap! threads inc)))
            (.setDaemon true)))))))

(defn instrumented-pool
  [{:keys
    [generate
     destroy
     stats-callback
     max-queue-size
     sample-period
     control-period
     controller]
    :or {sample-period 10
         control-period 10000
         max-queue-size 65536}}]
  (let [^Pool$Controller c controller]
    (assert controller "must specify :controller")
    (assert generate   "must specify :generate")
    (assert destroy    "must specify :destroy")
    (Pool.
      (reify Pool$Generator
        (generate [_ k]
          (generate k))
        (destroy [_ k v]
          (when destroy
            (destroy k v))))

      (reify Pool$Controller
        (shouldIncrement [_ key objects-per-key total-objects]
          (.shouldIncrement c key objects-per-key total-objects))
        (adjustment [_ key->stats]
          (when stats-callback
            (stats-callback
              (zipmap
                (map str (keys key->stats))
                (map stats->map (vals key->stats)))))
          (.adjustment c key->stats)))

      max-queue-size
      sample-period
      control-period
      TimeUnit/MILLISECONDS)))

(defn fixed-pool
  "Returns a "
  [generate destroy max-objects-per-key max-objects]
  (instrumented-pool
    {:generate generate
     :destroy destroy
     :controller (Pools/fixedController max-objects-per-key max-objects)}))

(defn utilization-pool
  [generate destroy target-utilization max-objects-per-key max-objects]
  (instrumented-pool
    {:generate generate
     :destroy destroy
     :controller (Pools/utilizationController target-utilization max-objects-per-key max-objects)}))

(defn acquire
  "Acquires an object from the pool for key `k`, returning a deferred containing the object."
  [^Pool p k]
  (let [d (d/deferred)]
    (try
      (.acquire p k
        (reify Pool$AcquireCallback
          (handleObject [_ obj]
            (when-not (d/success! d obj)
              (.release p k obj)))))
      (catch Throwable e
        (d/error! d e)))
    d))

(defn release
  "Releases an object for key `k` back to the pool."
  [^Pool p k obj]
  (.release p k obj))

(defn dispose
  "Disposes of a pooled object which is no longer valid."
  [^Pool p k obj]
  (.dispose p k obj))

(defn instrumented-executor
  "Returns a `java.util.concurrent.ExecutorService`, using Dirigiste.  A Dirigiste
   `controller` may be specified, as well as a `stats-callback` which will be invoked
   with a map of sampled metrics."
  [{:keys
    [thread-factory
     queue-length
     stats-callback
     sample-period
     control-period
     controller
     metrics
     initial-thread-count]
    :or {initial-thread-count 1
         sample-period 25
         control-period 10000
         metrics (EnumSet/allOf Stats$Metric)}}]
  (let [^Executor$Controller c controller
        metrics (if (identical? :none metrics)
                  (EnumSet/noneOf Stats$Metric)
                  metrics)]
    (assert controller "must specify :controller")
    (Executor.
      (or thread-factory (create-thread-factory))
      (if queue-length
        (ArrayBlockingQueue. queue-length)
        (SynchronousQueue.))
      (if stats-callback
        (reify Executor$Controller
          (shouldIncrement [_ n]
            (.shouldIncrement c n))
          (adjustment [_ s]
            (stats-callback (stats->map s))
            (.adjustment c s)))
        c)
      initial-thread-count
      metrics
      sample-period
      control-period
      TimeUnit/MILLISECONDS)))

(defn fixed-thread-executor
  "Returns an executor which has a fixed number of threads."
  ([num-threads]
     (fixed-thread-executor num-threads nil))
  ([num-threads options]
     (instrumented-executor
       (assoc options
         :max-threads num-threads
         :controller (reify Executor$Controller
                       (shouldIncrement [_ n]
                         (< n num-threads))
                       (adjustment [_ s]
                         (- num-threads (.getNumWorkers s))))))))

(defn utilization-executor
  "Returns an executor which sizes the thread pool according to target utilization, within
   `[0,1]`, up to `max-threads`."
  ([utilization max-threads]
     (utilization-executor utilization max-threads nil))
  ([utilization max-threads options]
     (instrumented-executor
       (assoc options
         :max-threads max-threads
         :controller (Executors/utilizationController utilization max-threads)))))
