(ns aleph.udp-test
  (:require
   [aleph.netty :as netty]
   [aleph.udp :as udp]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [manifold.stream :as s])
  (:import
   (java.net ServerSocket)))

(netty/leak-detector-level! :paranoid)

(def ^:dynamic *port* nil)

(defn rand-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn random-port-fixture [f]
  (binding [*port* (rand-port)]
    (f)))

(use-fixtures :each random-port-fixture)

(deftest test-echo
  (let [s @(udp/socket {:port *port*})]
    (s/put! s {:host "localhost", :port *port*, :message "foo"})
    (is (= "foo"
           (bs/to-string
            (:message
             @(s/take! s)))))
    (s/close! s)))

(deftest test-transport
  (testing "epoll"
    (try
      (let [s @(udp/socket {:port *port* :transport :epoll})]
        (try
          (s/put! s {:host "localhost", :port *port*, :message "foo"})
          (is (= "foo"
                 (bs/to-string
                  (:message
                   @(s/take! s)))))
          (finally
            (s/close! s))))
      (catch Exception _
        (is (not (netty/epoll-available?))))))

  (testing "kqueue"
    (try
      (let [s @(udp/socket {:port *port* :transport :kqueue})]
        (try
          (s/put! s {:host "localhost", :port *port*, :message "foo"})
          (is (= "foo"
                 (bs/to-string
                  (:message
                   @(s/take! s)))))
          (finally
            (s/close! s))))
      (catch Exception _
        (is (not (netty/kqueue-available?))))))

  (testing "io-uring"
    (try
      (let [s @(udp/socket {:port *port* :transport :io-uring})]
        (try
          (s/put! s {:host "localhost", :port *port*, :message "foo"})
          (is (= "foo"
                 (bs/to-string
                  (:message
                   @(s/take! s)))))
          (finally
            (s/close! s))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))
