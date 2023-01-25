(ns aleph.netty-test
  (:require
   [aleph.netty :as netty]
   [clojure.test :refer [deftest is]]
   [manifold.stream :as s])
  (:import
   (io.netty.channel Channel ChannelConfig)
   (io.netty.channel.embedded EmbeddedChannel)))

(deftest closing-a-channel-sink
  (let [ch (EmbeddedChannel.)
        s (netty/sink ch)]
    (is (= true @(s/put! s "foo")))
    (is (nil? @(netty/wrap-future (netty/close ch))))
    (is (= false @(s/put! s "foo")))))

(defn make-channel ^Channel []
  (let [auto-read (atom true)]
    (reify Channel
      (config [_]
        (reify ChannelConfig
          (isAutoRead [_]
            @auto-read)
          (setAutoRead [this b]
            (reset! auto-read b)
            this))))))

(deftest reader-backpressure
  (let [channel (make-channel)
        config  (.config channel)
        s       (s/stream 3)]
    (is (-> config .isAutoRead))
    (netty/put! channel s 1)
    (is (-> config .isAutoRead))
    (netty/put! channel s 2)
    (is (-> config .isAutoRead))
    (netty/put! channel s 3)
    (is (-> config .isAutoRead))
    (netty/put! channel s 4)
    (is (not (-> config .isAutoRead)))
    (s/take! s)
    (is (-> config .isAutoRead))
    (netty/put! channel s 5)
    (is (not (-> config .isAutoRead)))
    (s/take! s)
    (is (-> config .isAutoRead))
    (netty/put! channel s 6)
    (is (not (-> config .isAutoRead)))))
