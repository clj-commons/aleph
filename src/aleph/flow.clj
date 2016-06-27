(ns aleph.flow
  (:require
    [potemkin :as p]
    [manifold
     [deferred :as d]
     [executor :as ex]])
  (:import
    [io.aleph.dirigiste
     Pool
     IPool
     IPool$AcquireCallback
     IPool$Controller
     IPool$Generator
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

(defn instrumented-pool
  "Returns a [Dirigiste](https://github.com/ztellman/dirigiste) object pool, which can be interacted
   with via `acquire`, `release`, and `dispose`.

   |:---|:----
   | `generate` | a single-arg funcion which takes a key, and returns an object which should be non-equal to any other generated object |
   | `destroy` | an optional two-arg function which takes a key and object, and releases any associated resources |
   | `stats-callback` | a function which will be invoked every `control-period` with a map of keys onto associated statistics |
   | `max-queue-size` | the maximum number of pending acquires per key that are allowed before `acquire` will start to throw a `java.util.concurrent.RejectedExecutionException`.
   | `sample-period` | the interval, in milliseconds, between sampling the state of the pool for resizing and gathering statistics, defaults to `10`.
   | `control-period` | the interval, in milliseconds, between use of the controller to adjust the size of the pool, defaults to `10000`.
   | `controller` | a Dirigiste controller that is used to gide the pool's size."
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
  (let [^IPool$Controller c controller]
    (assert controller "must specify :controller")
    (assert generate   "must specify :generate")
    (Pool.
      (reify IPool$Generator
        (generate [_ k]
          (generate k))
        (destroy [_ k v]
          (when destroy
            (destroy k v))))

      (reify IPool$Controller
        (shouldIncrement [_ key objects-per-key total-objects]
          (.shouldIncrement c key objects-per-key total-objects))
        (adjustment [_ key->stats]
          (when stats-callback
            (stats-callback
              (zipmap
                (map str (keys key->stats))
                (map ex/stats->map (vals key->stats)))))
          (.adjustment c key->stats)))

      max-queue-size
      sample-period
      control-period
      TimeUnit/MILLISECONDS)))

(defn acquire
  "Acquires an object from the pool for key `k`, returning a deferred containing the object.  May
   throw a `java.util.concurrent.RejectedExecutionException` if there are too many pending acquires."
  [^IPool p k]
  (let [d (d/deferred nil)]
    (try
      (.acquire p k
        (reify IPool$AcquireCallback
          (handleObject [_ obj]
            (when-not (d/success! d obj)
              (.release p k obj)))))
      (catch Throwable e
        (d/error! d e)))
    d))

(defn release
  "Releases an object for key `k` back to the pool."
  [^IPool p k obj]
  (.release p k obj))

(defn dispose
  "Disposes of a pooled object which is no longer valid."
  [^IPool p k obj]
  (.dispose p k obj))

(p/import-vars
  [manifold.executor
   instrumented-executor
   utilization-executor
   fixed-thread-executor])
