(ns aleph.http.pipeline-stress-test
  "Stress tests for Netty 4.2 pipeline behavior under concurrent HTTP/2 streams.
   Validates that pipeline modifications (handler add/remove) under load do not
   cause race conditions or ConcurrentModificationExceptions with Netty 4.2's
   stack-flattened pipeline. Tagged :stress - excluded from default test runs.
   Run with: lein test :stress"
  (:require
    [aleph.http :as http]
    [aleph.netty :as netty]
    [aleph.resource-leak-detector]
    [aleph.ssl :as test-ssl]
    [aleph.testutils]
    [clj-commons.byte-streams :as bs]
    [clojure.test :refer [deftest is testing]]
    [manifold.deferred :as d])
  (:import
    (java.io Closeable)))

(set! *warn-on-reflection* false)

(def ^:private stress-port 18443)

(def ^:private server-options
  {:port             stress-port
   :shutdown-timeout 0
   :http-versions    [:http2 :http1]
   :ssl-context      test-ssl/server-ssl-context-opts})

(defn- echo-handler
  "Simple echo handler that returns the request body."
  [req]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    (or (some-> (:body req) (bs/to-string)) "ok")})

(defn- slow-handler
  "Handler with artificial delay to increase overlap of concurrent streams."
  [_req]
  (let [d' (d/deferred)]
    (future
      (Thread/sleep (long (+ 1 (rand-int 10))))
      (d/success! d' {:status  200
                      :headers {"content-type" "text/plain"}
                      :body    "slow-ok"}))
    d'))

(defn- make-pool
  "Creates an HTTP connection pool for stress testing."
  []
  (http/connection-pool
    {:connection-options {:insecure?     true
                          :http-versions [:http2 :http1]}}))

(defmacro ^:private with-stress-server
  "Starts a server with the given handler and options, runs body, then cleans up."
  [handler opts & body]
  `(let [server# (http/start-server ~handler (merge server-options ~opts))
         pool# (make-pool)]
     (try
       (let [~'pool pool#]
         ~@body)
       (finally
         (.close ^Closeable server#)
         (netty/wait-for-close server#)
         (.shutdown pool#)))))

(deftest ^:stress test-concurrent-http2-streams
  (testing "N concurrent HTTP/2 requests over a single connection"
    (with-stress-server echo-handler {}
                        (let [n 100
                              url (str "https://localhost:" stress-port "/echo")
                              results (doall
                                        (for [i (range n)]
                                          (http/get url
                                                    {:pool      pool
                                                     :insecure? true
                                                     :body      (str "req-" i)})))]
                          (doseq [[i result] (map-indexed vector results)]
                            (let [resp @(d/timeout! result 10000 ::timeout)]
                              (is (not= ::timeout resp)
                                  (str "Request " i " timed out"))
                              (when (not= ::timeout resp)
                                (is (= 200 (:status resp))
                                    (str "Request " i " failed with status " (:status resp))))))))))

(deftest ^:stress test-concurrent-http2-with-slow-handlers
  (testing "Concurrent HTTP/2 streams with variable-latency handlers"
    (with-stress-server slow-handler {}
                        (let [n 50
                              url (str "https://localhost:" stress-port "/slow")
                              results (doall
                                        (for [_ (range n)]
                                          (http/get url {:pool pool :insecure? true})))]
                          (doseq [[i result] (map-indexed vector results)]
                            (let [resp @(d/timeout! result 30000 ::timeout)]
                              (is (not= ::timeout resp)
                                  (str "Slow request " i " timed out"))
                              (when (not= ::timeout resp)
                                (is (= 200 (:status resp)))
                                (is (= "slow-ok" (bs/to-string (:body resp)))))))))))

(deftest ^:stress test-rapid-connect-disconnect
  (testing "Rapid connection establishment and teardown"
    (with-stress-server echo-handler {}
                        (let [n 20]
                          (dotimes [i n]
                            ;; Each iteration creates a fresh pool (new connection)
                            (let [pool' (make-pool)]
                              (try
                                (let [resp @(d/timeout!
                                              (http/get (str "https://localhost:" stress-port "/ping")
                                                        {:pool pool' :insecure? true})
                                              10000 ::timeout)]
                                  (is (not= ::timeout resp)
                                      (str "Rapid connect iteration " i " timed out"))
                                  (when (not= ::timeout resp)
                                    (is (= 200 (:status resp)))))
                                (finally
                                  (.shutdown pool')))))))))

(deftest ^:stress test-mixed-http-versions
  (testing "Mixed HTTP/1.1 and HTTP/2 requests to same server"
    (with-stress-server echo-handler {}
                        (let [url (str "https://localhost:" stress-port "/mixed")
                              ;; HTTP/2 pool
                              h2-pool (http/connection-pool
                                        {:connection-options {:insecure? true :http-versions [:http2]}})
                              ;; HTTP/1.1 pool
                              h1-pool (http/connection-pool
                                        {:connection-options {:insecure? true :http-versions [:http1]}})]
                          (try
                            (let [h2-results (doall (for [_ (range 20)]
                                                      (http/get url {:pool h2-pool :insecure? true})))
                                  h1-results (doall (for [_ (range 20)]
                                                      (http/get url {:pool h1-pool :insecure? true})))]
                              (doseq [r (concat h2-results h1-results)]
                                (let [resp @(d/timeout! r 10000 ::timeout)]
                                  (is (not= ::timeout resp))
                                  (when (not= ::timeout resp)
                                    (is (= 200 (:status resp)))))))
                            (finally
                              (.shutdown h2-pool)
                              (.shutdown h1-pool)))))))

(aleph.resource-leak-detector/instrument-tests!)
(aleph.testutils/instrument-tests-with-dropped-error-deferred-detection!)
