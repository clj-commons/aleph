(ns aleph.netty-test
  (:require
   [aleph.netty :as netty]
   [aleph.resource-leak-detector]
   [aleph.testutils]
   [clojure.test :refer [deftest is testing]]
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
  ;; Ensure `autoRead` is disabled as soon as the manifold stream is full to
  ;; ensure a single pending put.
  ;; When `autoRead` is enabled, calls to `ChannelHandlerContext#read` are
  ;; automatically performed, fetching the data from the associated `Channel`. To
  ;; ensure the backpressure is applied, `autoRead` is disabled as soon as
  ;; the `manifold.stream/put!` returns an unrealized deferred.
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

(deftest sliced-bytebuf-conversion
  (let [s "foObar"
        bbuf (netty/to-byte-buf s)
        sbuf (.slice bbuf 2 3)
        bary (netty/buf->array bbuf)
        sary (netty/buf->array sbuf)]
    (testing "Vanilla ByteBuf conversion"
      (is (= s (String. ^bytes bary))))
    (testing "Sliced ByteBuf conversion"
      (is (= (subs s 2 5) (String. ^bytes sary))))))

(aleph.resource-leak-detector/instrument-tests!)
(aleph.testutils/instrument-tests-with-dropped-error-deferred-detection!)
