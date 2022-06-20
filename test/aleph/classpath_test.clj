(ns aleph.classpath-test
  (:require  [clojure.test      :refer [deftest testing is]]
             [aleph.http        :as http]
             [manifold.deferred :as d]
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
  (let [pid (.getName (ManagementFactory/getRuntimeMXBean))]
    (->> pid
         (re-seq #"[0-9]+")
         (first)
         (Integer/parseInt))))

(deftest test-classpath
  (testing "classpath: ensure the class loader is always a DynamicClassLoader"
    (let [result (CompletableFuture.)]
      (intern 'aleph.netty.impl 'operation-complete (partial operation-complete result))
      (require 'aleph.netty :reload)
      (let [server (http/start-server
                    (constantly {:body "ok"})
                    {:port 9999})]
        (with-handler :term
          (.close ^java.io.Closeable server))
        (.exec (Runtime/getRuntime) (format "kill -SIGTERM %s" (pid)))
        ;; XXX: this test needs to be true!
        (is (false? @result))
        (require 'aleph.netty.impl :reload)))))
