;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.trace
  (:use
    [clojure test]
    [aleph.test utils]
    [lamina core connections trace executor])
  (:require
    [clojure.tools.logging :as log]
    [aleph.trace :as t]
    [aleph.formats :as formats]))

;;;

(defmacro with-router [port & body]
  `(let [stop-server# (t/start-trace-router {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)
         (Thread/sleep 500)))))

(defmacro with-endpoint [[e] port & body]
  `(let [~e (t/trace-endpoint
              {:aggregation-period 1000
               :client-options {:host "localhost", :port ~port}})]
     (try
       ~@body
       (finally
         (close-connection ~e)))))

;;;

(defn subscribe [client destination]
  (let [{:keys [messages errors]} (t/subscribe client destination)]
    (receive-all errors #(log/error %))
    messages))

(defn next-msg [ch]
  (-> ch read-channel (wait-for-result 6000)))

(defn next-non-zero-msg [ch]
  (->> (repeatedly #(next-msg ch))
    (drop-while zero?)
    first))

(deftest test-basic-routing
  (with-router 10000
    (with-endpoint [c1] 10000

      (let [a (subscribe c1 "ab*")
            b (subscribe c1 "*ef")]

        ;; wait for subscriptions to propagate
        (Thread/sleep 500)

        (is (= true (trace :abc 1)))
        (is (= true (trace :def 2)))
        (is (= 1 (next-msg a)))
        (is (= 2 (next-msg b)))

        ;; unsubscribe, and wait to propagate
        (close a)
        (Thread/sleep 500)
        
        (is (= false (trace :abc 3)))
        (is (= true (trace :def 4)))
        (is (= 4 (next-msg b)))

        (with-endpoint [c2] 10000

          ;; wait for existing subscriptions to propagate
          (Thread/sleep 500)

          ;; this should double broadcast
          (is (= true (trace :def 5)))
          (is (= 5 (next-msg b)))
          (is (= 5 (next-msg b))))))
    
    (Thread/sleep 500)

    (is (= false (trace :def 6)))))

(deftest test-basic-operators
  (with-router 10000
    (with-endpoint [c] 10000

      (let [sum (subscribe c "abc.x.y.sum()")
            sum* (subscribe c "abc.select(a: x.y, b: x).a.sum()")
            filtered-sum* (subscribe c "abc.where(x.y > 1).x.y.sum()")
            filtered-sum** (subscribe c "abc.x.where(y = 4).y.sum()")
            filtered-sum*** (subscribe c "abc.x.y.where(_ < 4).sum()")
            avg (subscribe c "abc.x.y.moving-average()")
            rate (subscribe c "abc.rate(period: 1000)")
            sum-avg (subscribe c "abc.x.y.sum().moving-average(period: 1000)")
            lookup (subscribe c "abc.x.y")]

        (Thread/sleep 500)

        (doseq [x (range 1 5)]
          (is (= true (trace :abc {:x {:y x}}))))

        (is (= 10 (next-non-zero-msg sum) (next-non-zero-msg sum*)))
        (is (= 9 (next-non-zero-msg filtered-sum*)))
        (is (= 4 (next-non-zero-msg filtered-sum**)))
        (is (= 6 (next-non-zero-msg filtered-sum***)))
        (is (= 4 (next-non-zero-msg rate)))
        (is (= 2.5 (next-non-zero-msg avg)))
        (is (= 10.0 (next-non-zero-msg sum-avg)))
        (is (= (range 1 5) (take 4 (repeatedly #(next-non-zero-msg lookup)))))

        (Thread/sleep 1000)

        (doseq [x (range 6 10)]
          (is (= true (trace :abc {:x {:y x}}))))

        (is (= 30 (next-non-zero-msg sum)))))))

(deftest test-group-by
  (with-router 10000
    (with-endpoint [c] 10000

      (is (= false (trace :abc 1)))
      
      (let [foo-grouping (subscribe c "abc.group-by(foo)")
            foo-rate (subscribe c "abc.group-by(foo).rate()")
            bar-rate (subscribe c "abc.group-by(facet: bar).rate()")
            bar-rate* (subscribe c "abc.select(foo, bar).group-by(bar).rate()")
            bar-rate** (subscribe c "abc.select(bar).group-by(bar).bar.rate()")
            foo-bar-rate (subscribe c "abc.group-by(foo).select(bar).group-by(bar).rate()")
            foo-bar-rate* (subscribe c "abc.group-by([foo bar]).rate()")
            val (fn [foo bar] {:foo foo, :bar bar})]

        (Thread/sleep 500)

        (doseq [x (map val [:a :a :b :b :c] [:x :x :z :y :y])]
          (is (= true (trace :abc x))))

        (is (= {:a [:x :x], :b [:z :y], :c [:y]}
              (let [m (next-msg foo-grouping)]
                (zipmap (keys m) (map #(map :bar %) (vals m))))))
        (is (= {:a 2, :b 2, :c 1}
              (next-msg foo-rate)))
        (is (= {:x 2, :y 2, :z 1}
              (next-msg bar-rate) (next-msg bar-rate*) (next-msg bar-rate**)))
        (is (= {:c {:y 1}, :b {:y 1, :z 1}, :a {:x 2}}
              (next-msg foo-bar-rate)))
        (is (= {[:a :x] 2, [:b :z] 1, [:c :y] 1, [:b :y] 1}
              (next-msg foo-bar-rate*)))))

    (Thread/sleep 500)

    (is (= false (trace :abc {:foo 1, :bar 1})))))
