# Replace internal scheduler

## Default scheduler

The default scheduler of Manifold (and thus Aleph) is not efficient for handling high volume scheduling requests (+100k/sec) as it's based on [ScheduledThreadPoolExecutor](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ScheduledExecutorService.html) which uses blocking queues.

For optimized performance, you can provide a custom scheduler by redefining `manifold.time/*clock*`.
This custom executor must implement the [IClock](https://github.com/clj-commons/manifold/blob/de2f9b4cfc4e8260a1e6ba2c0343206eb07cc750/src/manifold/time.clj#L131-L133) protocol which comprises two functions: `in` and `every`.

The `in` function allows you to schedule a task to be executed after a specified amount of time. It takes two arguments: the number of milliseconds to wait before executing the task, and the task itself.

The `every` function schedules a task to be executed repeatedly at a specified interval. It takes three arguments: the initial delay in milliseconds before the first execution, the number of milliseconds between executions, and the task to be executed.

## Netty HashedWheelTimer

Netty offers a high-performance timer for scheduling approximate I/O timeouts, 
the `io.netty.util.HashedWheelTimer`. It utilizes [JCTools MPSC lockless queues](https://github.com/JCTools/JCTools/wiki/Getting-Started-With-JCTools) for efficient
operations. The timer can be easily integrated as an alternative scheduler.

Here is an example with a time accuracy of 10 milliseconds:

```clojure
(import  '[java.util.concurrent Executors TimeUnit])
(import  '[io.netty.util HashedWheelTimer TimerTask])
(import  '[manifold.time IClock])
(require '[aleph.netty :refer [enumerating-thread-factory]])
(require '[manifold.time :as mtime])

(def hashed-timer-clock
  (let [timer (HashedWheelTimer.
               (enumerating-thread-factory "manifold-timeout-scheduler" false)
               10 TimeUnit/MILLISECONDS 1024)
        periodic-clock (mtime/scheduled-executor->clock
                        (Executors/newSingleThreadScheduledExecutor
                         (enumerating-thread-factory "manifold-periodic-scheduler" false)))]
    (reify IClock
      (in [_ interval f]
        (.newTimeout timer (reify TimerTask (run [_ _] (f)))
                     interval TimeUnit/MILLISECONDS))
      (every [_ delay period f]
        (.every ^IClock periodic-clock delay period f)))))

(alter-var-root #'mtime/*clock* (constantly hashed-timer-clock))
```


