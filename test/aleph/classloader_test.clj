(ns aleph.classloader-test
  (:require  [clojure.test      :refer [deftest testing is]]
             [aleph.http        :as http]
             [aleph.netty       :as netty]
             [manifold.deferred :as d]
             [manifold.utils    :refer [when-class]]
             [signal.handler    :refer [with-handler]])
  (:import [io.netty.util.concurrent Future]
           [java.lang.management ManagementFactory]
           [java.util.concurrent CompletableFuture]))

(defn- operation-complete
  "Stubs for `GenericFutureListener/operationComplete` which
  returns a completed `CompletableFuture` containing either
  `true` or `false` in case of Throwable."
  [^CompletableFuture result ^Future f d]
  (try (d/success! d (.getNow f))
       (.complete result true)
       d
       (catch Throwable _ (do (.complete result false) d))))

(defn pid
  "Gets this process' PID."
  []
  (if-let [pid (when-class java.lang.ProcessHandle
                 (.pid (java.lang.ProcessHandle/current)))]
    pid
    (let [pid (.getName (ManagementFactory/getRuntimeMXBean))]
      (->> pid
           (re-seq #"[0-9]+")
           (first)
           (Integer/parseInt)))))

(deftest test-classloader
  (testing "classloader: ensure the class loader is always a DynamicClassLoader"
    (let [result (CompletableFuture.)]
      (with-redefs [netty/operation-complete (partial operation-complete result)]
        (let [server (http/start-server
                      (constantly {:body "ok"})
                      {:port 9999})]
          (with-handler :int
            (.close ^java.io.Closeable server))
          (.exec (Runtime/getRuntime) (format "kill -SIGINT %s" (pid)))
          (is @result))))))
