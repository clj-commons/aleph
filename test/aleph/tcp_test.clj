(ns aleph.tcp-test
  (:require
   [aleph.netty :as netty]
   [aleph.resource-leak-detector]
   [aleph.tcp :as tcp]
   [aleph.testutils :refer [bound-channel]]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest testing is]]
   [manifold.stream :as s]))

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

(deftest test-listen-socket
  (testing "the :port option is not required when :listen-socket is passed"
    (let [port 8083]
      (with-open [channel (bound-channel port)]
        (with-server (tcp/start-server echo-handler {:listen-socket channel :shutdown-timeout 0})
          (let [c @(tcp/client {:host "localhost" :port port})]
            (s/put! c "foo")
            (is (= "foo" (bs/to-string @(s/take! c))))))))))

(aleph.resource-leak-detector/instrument-tests!)
(aleph.testutils/instrument-tests-with-dropped-error-deferred-detection!)
