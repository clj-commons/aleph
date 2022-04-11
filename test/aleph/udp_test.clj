(ns aleph.udp-test
  (:use
    [clojure test])
  (:require
    [manifold.stream :as s]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [aleph.udp :as udp]))

(netty/leak-detector-level! :paranoid)

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(deftest test-echo
  (let [s @(udp/socket {:port 10001, :epoll? true})]
    (s/put! s {:host "localhost", :port 10001, :message "foo"})
    (is (= "foo"
          (bs/to-string
            (:message
              @(s/take! s)))))
    (s/close! s)))
