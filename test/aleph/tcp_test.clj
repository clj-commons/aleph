(ns aleph.tcp-test
  (:require
   [aleph.netty :as netty]
   [aleph.tcp :as tcp]
   [aleph.utils :refer [rand-port]]
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
  (let [port (rand-port)]
    (with-server (tcp/start-server echo-handler {:port port :shutdown-timeout 0})
      (let [c @(tcp/client {:host "localhost", :port port})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))))))

(deftest test-transport
  (testing "epoll"
    (let [port (rand-port)]
      (try (with-server (tcp/start-server echo-handler {:port port :shutdown-timeout 0 :transport :epoll})
             (let [c @(tcp/client {:host "localhost", :port port :transport :epoll})]
               (s/put! c "foo")
               (is (= "foo" (bs/to-string @(s/take! c))))))
           (catch Exception _
             (is (not (netty/epoll-available?)))))))

  (testing "kqueue"
    (let [port (rand-port)]
      (try (with-server (tcp/start-server echo-handler {:port port :shutdown-timeout 0 :transport :kqueue})
             (let [c @(tcp/client {:host "localhost", :port port :transport :kqueue})]
               (s/put! c "foo")
               (is (= "foo" (bs/to-string @(s/take! c))))))
           (catch Exception _
             (is (not (netty/kqueue-available?)))))))

  (testing "io-uring"
    (let [port (rand-port)]
      (try (with-server (tcp/start-server echo-handler {:port port :shutdown-timeout 0 :transport :io-uring})
             (let [c @(tcp/client {:host "localhost", :port port :transport :io-uring})]
               (s/put! c "foo")
               (is (= "foo" (bs/to-string @(s/take! c))))))
           (catch Exception _
             (is (not (netty/io-uring-available?))))))))
