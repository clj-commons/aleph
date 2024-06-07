(ns aleph.tcp-test
  (:require
   [aleph.netty :as netty]
   [aleph.resource-leak-detector]
   [aleph.tcp :as tcp]
   [aleph.testutils :refer [passive-tcp-server]]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as d]
   [manifold.stream :as s])
  (:import
   (java.util.concurrent TimeUnit)))

(defn echo-handler [s _]
  (s/connect s s))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(deftest test-echo
  (let [server (tcp/start-server echo-handler {:port 0 :shutdown-timeout 0})]
    (with-server server
      (let [c @(tcp/client {:host "localhost", :port (netty/port server)})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))))))

(deftest test-transport
  (testing "epoll"
    (try
      (let [server (tcp/start-server echo-handler {:port 0 :shutdown-timeout 0 :transport :epoll})]
        (with-server server
          (let [c @(tcp/client {:host "localhost", :port (netty/port server) :transport :epoll})]
            (s/put! c "foo")
            (is (= "foo" (bs/to-string @(s/take! c)))))))
      (catch Exception _
        (is (not (netty/epoll-available?))))))

  (testing "kqueue"
    (try
      (let [server (tcp/start-server echo-handler {:port 0 :shutdown-timeout 0 :transport :kqueue})]
        (with-server server
          (let [c @(tcp/client {:host "localhost", :port (netty/port server) :transport :kqueue})]
            (s/put! c "foo")
            (is (= "foo" (bs/to-string @(s/take! c)))))))
      (catch Exception _
        (is (not (netty/kqueue-available?))))))

  (testing "io-uring"
    (try
      (let [server (tcp/start-server echo-handler {:port 0 :shutdown-timeout 0 :transport :io-uring})]
        (with-server server
          (let [c @(tcp/client {:host "localhost", :port (netty/port server) :transport :io-uring})]
            (s/put! c "foo")
            (is (= "foo" (bs/to-string @(s/take! c)))))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))

(deftest test-cancellation-during-connection-establishment
  (let [connect-client @#'aleph.netty/connect-client
        connect-future (promise)
        server (passive-tcp-server 0)]
    (with-redefs [aleph.netty/connect-client (fn [& args]
                                               (let [fut (apply connect-client args)]
                                                 (deliver connect-future fut)
                                                 fut))]
      (with-server server
        (let [c (tcp/client {:host "localhost"
                             :port (netty/port server)})]
          (is (some? (deref connect-future 1000 nil)))
          (d/timeout! c 10)
          (some-> @connect-future (.await 2000 TimeUnit/MILLISECONDS))
          (is (some-> @connect-future .isSuccess false?))
          (is (some-> @connect-future .isDone))
          (is (some-> @connect-future .isCancelled)))))))

(aleph.resource-leak-detector/instrument-tests!)
