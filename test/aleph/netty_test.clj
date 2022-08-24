(ns aleph.netty-test
  (:require
   [aleph.netty :as netty]
   [clojure.test :refer [deftest is]]
   [manifold.stream :as s])
  (:import
   (io.netty.channel.embedded EmbeddedChannel)))

(deftest closing-a-channel-sink
  (let [ch (EmbeddedChannel.)
        s (netty/sink ch)]
    (is (= true @(s/put! s "foo")))
    (is (nil? @(netty/wrap-future (netty/close ch))))
    (is (= false @(s/put! s "foo")))))
