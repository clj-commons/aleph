(ns aleph.tcp-test
  (:require
   [aleph.netty :as netty]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest testing is]]
   [manifold.stream :as s]))

(netty/leak-detector-level! :paranoid)

(defn echo-handler [s _]
  (s/connect s s))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(deftest test-echo
  (with-server (tcp/start-server echo-handler {:port 10001 :shutdown-timeout 0})
    (let [c @(tcp/client {:host "localhost", :port 10001})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))

(deftest test-transport
  (testing "epoll"
    (try (with-server (tcp/start-server echo-handler {:port 10001 :shutdown-timeout 0 :transport :epoll})
           (let [c @(tcp/client {:host "localhost", :port 10001 :transport :epoll})]
             (s/put! c "foo")
             (is (= "foo" (bs/to-string @(s/take! c))))))
         (catch Exception _
           (is (not (netty/epoll-available?))))))

  (testing "kqueue"
    (try (with-server (tcp/start-server echo-handler {:port 10001 :shutdown-timeout 0 :transport :kqueue})
           (let [c @(tcp/client {:host "localhost", :port 10001 :transport :kqueue})]
             (s/put! c "foo")
             (is (= "foo" (bs/to-string @(s/take! c))))))
         (catch Exception _
           (is (not (netty/kqueue-available?))))))

  (testing "io-uring"
    (try (with-server (tcp/start-server echo-handler {:port 10001 :shutdown-timeout 0 :transport :io-uring})
           (let [c @(tcp/client {:host "localhost", :port 10001 :transport :io-uring})]
             (s/put! c "foo")
             (is (= "foo" (bs/to-string @(s/take! c))))))
         (catch Exception _
           (is (not (netty/io-uring-available?)))))))
