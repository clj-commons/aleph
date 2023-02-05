(ns aleph.udp-test
  (:require
   [aleph.netty :as netty]
   [aleph.udp :as udp]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest testing is]]
   [manifold.stream :as s]))

(netty/leak-detector-level! :paranoid)

(deftest test-echo
  (let [s @(udp/socket {:port 10001})]
    (s/put! s {:host "localhost", :port 10001, :message "foo"})
    (is (= "foo"
           (bs/to-string
             (:message
              @(s/take! s)))))
    (s/close! s)))

(deftest test-transport
  (testing "epoll"
    (try
      (let [s @(udp/socket {:port 10001 :transport :epoll})]
        (try
          (s/put! s {:host "localhost", :port 10001, :message "foo"})
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
      (let [s @(udp/socket {:port 10001 :transport :kqueue})]
        (try
          (s/put! s {:host "localhost", :port 10001, :message "foo"})
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
      (let [s @(udp/socket {:port 10001 :transport :io-uring})]
        (try
          (s/put! s {:host "localhost", :port 10001, :message "foo"})
          (is (= "foo"
                 (bs/to-string
                  (:message
                   @(s/take! s)))))
          (finally
            (s/close! s))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))
