(ns aleph.netty.impl
  (:import [io.netty.util.concurrent Future]))

(defmacro operation-complete [^Future f d]
  `(cond
     (.isSuccess ~f)
     (d/success! ~d (.getNow ~f))

     (.isCancelled ~f)
     (d/error! ~d (CancellationException. "future is cancelled."))

     (some? (.cause ~f))
     (if (instance? java.nio.channels.ClosedChannelException (.cause ~f))
       (d/success! ~d false)
       (d/error! ~d (.cause ~f)))

     :else
     (d/error! ~d (IllegalStateException. "future in unknown state"))))
