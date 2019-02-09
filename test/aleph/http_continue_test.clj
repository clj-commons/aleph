(ns aleph.http-continue-test
  (:use [clojure test])
  (:require [aleph
             [http :as http]
             [netty :as netty]
             [flow :as flow]
             [tcp :as tcp]]
            [byte-streams :as bs]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.string :as str])
  (:import [java.util.concurrent ExecutorService]))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)
         (netty/wait-for-close server#)))))

(def port 8082)

(defn ok-handler [_]
  {:status 200
   :body "OK"})

(defn pack-lines [lines]
  (str (str/join "\r\n" lines) "\r\n\r\n"))

(defn- run-test [server-options]
  (with-server (http/start-server ok-handler (merge
                                              server-options
                                              {:port port}))
    (let [c @(tcp/client {:host "localhost" :port port})]
      @(s/put! c (pack-lines ["PUT /file HTTP/1.1"
                              "Host: localhost"
                              "Content-Length: 3"
                              "Expect: 100-continue"]))
      (let [resp-line @(s/try-take! c ::drained 1e3 ::timeout)]
        (is (str/includes? (bs/to-string resp-line) "100 Continue")))
      @(s/put! c (pack-lines ["OK?"]))
      (let [finish-line @(s/try-take! c ::drained 1e3 ::timeout)]
        (is (str/includes? (bs/to-string finish-line) "OK"))))))

(deftest test-default-continue-handler
  (run-test {}))

(deftest test-custom-continue-handler-accept-all
  (testing "custom handler with realized response"
    (run-test {:continue-handler (constantly true)}))

  (testing "custom handler with deferred response"
    (run-test {:continue-handler (constantly (d/success-deferred true))}))

  (testing "custom handler with custom executor"
    (let [exec (flow/utilization-executor 0.9 512)]
      (run-test {:continue-handler (constantly (d/success-deferred true))
                 :continue-executor exec})))

  (testing "custom handler with inlined execution"
    (run-test {:continue-handler (constantly true)
               :continue-executor :none})))
