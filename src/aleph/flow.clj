(ns aleph.flow
  (:import
    [io.aleph.dirigiste
     Executor
     Executor$Metric
     Controllers
     Controller
     Stats]
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
         (when (contains? stats Executor$Metric/QUEUE_LENGTH)
           {:queue-length (q #(.getQueueLength s %))})
         (when (contains? stats Executor$Metric/QUEUE_LATENCY)
           {:queue-latency (q #(double (/ (.getQueueLatency s %) 1e6)))})
         (when (contains? stats Executor$Metric/TASK_LATENCY)
           {:task-latency (q #(double (/ (.getTaskLatency s %) 1e6)))})
         (when (contains? stats Executor$Metric/TASK_ARRIVAL_RATE)
           {:task-arrival-rate (q #(.getTaskArrivalRate s %))})
         (when (contains? stats Executor$Metric/TASK_COMPLETION_RATE)
           {:task-completion-rate (q #(.getTaskCompletionRate s %))})
         (when (contains? stats Executor$Metric/TASK_REJECTION_RATE)
           {:task-rejection-rate (q #(.getTaskRejectionRate s %))})
         (when (contains? stats Executor$Metric/UTILIZATION)
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
     metrics]
    :or {sample-period 25
         control-period 10000
         metrics (EnumSet/allOf Executor$Metric)}}]
  (let [^Controller c controller]
    (Executor.
      (or thread-factory (create-thread-factory))
      (if queue-length
        (ArrayBlockingQueue. queue-length)
        (SynchronousQueue.))
      (if stats-callback
        (reify Controller
          (shouldIncrement [_ n]
            (.shouldIncrement c n))
          (adjustment [_ s]
            (stats-callback (stats->map s))
            (.adjustment c s)))
        c)
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
         :controller (reify Controller
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
         :controller (Controllers/utilization utilization max-threads)))))
