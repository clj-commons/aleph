(ns aleph.udp-test
  (:require
   [aleph.netty :as netty]
   [aleph.udp :as udp]
   [aleph.utils :refer [rand-port]]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest testing is]]
   [manifold.stream :as s]))

(netty/leak-detector-level! :paranoid)

(deftest test-echo
  (let [port (rand-port)
        s @(udp/socket {:port port})]
    (s/put! s {:host "localhost", :port port, :message "foo"})
    (is (= "foo"
           (bs/to-string
            (:message
             @(s/take! s)))))
    (s/close! s)))

(deftest test-transport
  (testing "epoll"
    (try
      (let [port (rand-port)
            s @(udp/socket {:port port :transport :epoll})]
        (try
          (s/put! s {:host "localhost", :port port, :message "foo"})
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
      (let [port (rand-port)
            s @(udp/socket {:port port :transport :kqueue})]
        (try
          (s/put! s {:host "localhost", :port port, :message "foo"})
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
      (let [port (rand-port)
            s @(udp/socket {:port port :transport :io-uring})]
        (try
          (s/put! s {:host "localhost", :port port, :message "foo"})
          (is (= "foo"
                 (bs/to-string
                  (:message
                   @(s/take! s)))))
          (finally
            (s/close! s))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))
