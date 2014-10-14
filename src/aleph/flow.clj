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
          (Thread. r (str "aleph-pool-" factory "-" (swap! threads inc))))))))

(defn executor
  [{:keys
    [thread-factory
     queue-size
     max-threads
     target-utilization
     stats-callback
     sample-period
     control-period]
    :or {utilization 0.9
         max-threads 256
         sample-period 25
         control-period 10000}}]
  (let [c (Controllers/utilization 0.9 max-threads)]
    (Executor.
      (or thread-factory (create-thread-factory))
      (if queue-size
        (ArrayBlockingQueue. queue-size)
        (SynchronousQueue.))
      (if stats-callback
        (reify Controller
          (shouldIncrement [_ n]
            (.shouldIncrement c n))
          (adjustment [_ s]
            (stats-callback (stats->map s))
            (.adjustment c s)))
        c)
      (EnumSet/allOf Executor$Metric)
      sample-period
      control-period
      TimeUnit/MILLISECONDS)))
