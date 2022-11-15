(ns aleph.http-timeout-test
  (:require [aleph.http   :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as s])
  (:import java.lang.AutoCloseable))

(def ^:private default-options
  {:throw-exceptions? false
   :pool (http/connection-pool {:connection-options {:keep-alive? false}})})

(def ^:private port 8082)

(defn- http-get
  [& {:as options}]
  (http/get (str "http://localhost:" port) (merge default-options options)))

(defn- streaming-handler
  [_]
  (let [body (s/stream)]
    (future
      (dotimes [i 10]
        (s/put! body (str i "."))
        (Thread/sleep 200))
      (s/close! body))
    {:status 200
     :body body}))

(defn- waiting-handler
  [_]
  (Thread/sleep 2000)
  {:status 200
   :body   "waited"})

(defn- server-options
  [& {:as options}]
  (merge {:port 8082 :shutdown-timeout 4}
         options))

(deftest test-shutdown-timeout-1
  (testing "shutdown and wait for streaming in-flight requests to finish."
    (let [server (http/start-server streaming-handler (server-options))]
      (try
        (let [resp (http-get)]
          (Thread/sleep 50) ;; wait a bit for the request to be initiated
          (.close ^AutoCloseable server)
          (is (= 200 (:status @resp)))
          (is (= "0.1.2.3.4.5.6.7.8.9." (bs/to-string (:body @resp)))))
        (finally
          (.close ^AutoCloseable server)))))

  (testing "shutdown and wait for blocking in-flight requests to finish."
    (let [server (http/start-server waiting-handler (server-options :shutdown-timeout 4))]
      (try
        (let [resp (http-get)]
          (Thread/sleep 50) ;; wait a bit for the request to be initiated
          (.close ^AutoCloseable server)
          (is (= 200 (:status @resp)))
          (is (= "waited" (bs/to-string (:body @resp)))))
        (finally
          (.close ^AutoCloseable server))))))

(deftest test-shutdown-timeout-2
  (testing "shutdown with a timeout of 0 second and no grace for in-flight requests"
    (let [server (http/start-server waiting-handler (server-options :shutdown-timeout 0))]
      (try
        (let [resp (http-get)]
          (Thread/sleep 50) ;; wait a bit for the request to be initiated
          (.close ^AutoCloseable server)
          (is (thrown-with-msg? Exception #"connection was closed after 0\." @resp)))
        (finally
          (.close ^AutoCloseable server))))))

(deftest test-shutdown-timeout-3
  (testing "shutdown with a timeout of 1 seconds with streaming body"
    (let [server (http/start-server streaming-handler (server-options :shutdown-timeout 1))]
      (try
        (let [resp (http-get)]
          (Thread/sleep 50) ;; wait a bit for the request to be initiated
          (.close ^AutoCloseable server)
          (is (= 200 (:status @resp)))
          (is (= "0.1.2.3.4.5." (bs/to-string (:body @resp)))))
        (finally
          (.close ^AutoCloseable server))))))


(deftest test-shutdown-timeout-4
  (testing "shutdown with a timeout of 1 seconds while waiting for body"
    (let [server (http/start-server waiting-handler (server-options :shutdown-timeout 1))]
      (try
        (let [resp (http-get)]
          (Thread/sleep 50) ;; wait a bit for the request to be initiated
          (.close ^AutoCloseable server)
          (is (thrown-with-msg? Exception #"connection was closed after 1\." @resp)))
        (finally
          (.close ^AutoCloseable server))))))

