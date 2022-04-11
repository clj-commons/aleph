(ns aleph.http-continue-test
  (:use [clojure test])
  (:require [aleph
             [http :as http]
             [netty :as netty]
             [flow :as flow]
             [tcp :as tcp]]
            [clj-commons.byte-streams :as bs]
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

(defn wait-for [client pattern]
  (let [packet @(s/try-take! client ::drained 1e3 ::timeout)]
    (is (not= ::drained packet))
    (is (not= ::timeout packet))
    (is (str/includes? (bs/to-string packet) pattern))))

(defn- test-accept [server-options]
  (with-server (http/start-server ok-handler (merge
                                              server-options
                                              {:port port}))
    (let [c @(tcp/client {:host "localhost" :port port})]
      @(s/put! c (pack-lines ["PUT /file HTTP/1.1"
                              "Host: localhost"
                              "Content-Length: 3"
                              "Expect: 100-continue"]))
      (wait-for c "100 Continue")
      @(s/put! c (pack-lines ["OK?"]))
      (wait-for c "OK"))))

(deftest test-default-continue-handler
  (test-accept {}))

(deftest test-custom-continue-handler-accept-all
  (testing "custom handler with realized response"
    (test-accept {:continue-handler (constantly true)}))

  (testing "custom handler with deferred response"
    (test-accept {:continue-handler (constantly (d/success-deferred true))}))

  (testing "custom handler with custom executor"
    (let [exec (flow/utilization-executor 0.9 512)]
      (test-accept {:continue-handler (constantly (d/success-deferred true))
                    :continue-executor exec})))

  (testing "custom handler with inlined execution"
    (test-accept {:continue-handler (constantly true)
                  :continue-executor :none})))

(defn- test-reject [server-options & [resp]]
  (let [resp (or resp "417 Expectation Failed")]
    (with-server (http/start-server ok-handler (merge
                                                server-options
                                                {:port port}))
      (let [c @(tcp/client {:host "localhost" :port port})]
        @(s/put! c (pack-lines ["PUT /file HTTP/1.1"
                                "Host: localhost"
                                "Content-Length: 3000"
                                "Expect: 100-continue"]))
        (wait-for c resp)))))

(deftest test-custom-continue-handler-reject-all
  (testing "custom handler with realized response"
    (test-reject {:continue-handler (constantly false)}))

  (testing "custom handler with deferred response"
    (test-reject {:continue-handler (constantly (d/success-deferred false))}))

  (testing "custom handler with custom executor"
    (let [exec (flow/utilization-executor 0.9 512)]
      (test-reject {:continue-handler (constantly (d/success-deferred false))
                    :continue-executor exec})))

  (testing "custom handler with inlined execution"
    (test-reject {:continue-handler (constantly false)
                  :continue-executor :none}))

  (testing "custom handler with custom realized response"
    (test-reject {:continue-handler (constantly {:status 417})})
    (test-reject {:continue-handler (constantly {:status 401})}
                 "401 Unauthorized")
    (test-reject {:continue-handler (constantly {:status 403
                                                 :headers {"X-Via" "Test"}})}
                 "X-Via: Test"))

  (testing "custom handler with custom deferred response"
    (test-reject {:continue-handler
                  (constantly (d/success-deferred {:status 401}))}
                 "401 Unauthorized")))
