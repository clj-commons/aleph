(ns aleph.udp-test
  (:use
    [clojure test])
  (:require
    [manifold.stream :as s]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [aleph.udp :as udp])
  (:import (io.netty.channel ChannelOption FixedRecvByteBufAllocator)))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(def words (slurp "/usr/share/dict/words"))

(deftest test-echo
  (let [s @(udp/socket {:port          10001
                        :netty-options [[ChannelOption/RCVBUF_ALLOCATOR (FixedRecvByteBufAllocator. (int 4096))]
                                        [ChannelOption/SO_RCVBUF (int 4096)]]})]
    (s/put! s {:host "localhost", :port 10001, :message "foo"})
    (is (= "foo"
          (bs/to-string
            (:message
              @(s/take! s)))))
    (s/close! s)))
