;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.redis
  (:use
    [clojure test]
    [lamina core connections]
    [aleph redis]
    [aleph.test utils]))

(def config {:host "localhost"})

(deftest ^:benchmark test-redis-roundtrip
  (let [r (redis-client config)]
    (bench "simple roundtrip"
      @(r [:ping]))
    (bench "1e3 roundtrips"
      @(apply merge-results (repeatedly 1e3 #(r [:ping]))))
    (close-connection r)))

(deftest test-task-channels
  (let [c1 (redis-client config)
        c2 (redis-client config)
        _ @(c1 [:del :q])
        emitter-channel (sink (partial enqueue-task c1 :q))
        receiver-channel (task-receiver-channel c2 :q)
        task {:foo "bar"}]
    (enqueue emitter-channel task)
    (is (= {:queue "q" :task task}
           @(read-channel receiver-channel)))))