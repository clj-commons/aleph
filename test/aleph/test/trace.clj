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
    [aleph trace]
    [aleph.test utils]
    [lamina core connections trace executor])
  (:require
    [aleph.formats :as formats]))

;;;

(defmacro with-router [port & body]
  `(let [stop-server# (start-trace-router {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)
         (Thread/sleep 500)))))

(defmacro with-endpoint [[e] port & body]
  `(let [~e (trace-endpoint
              {:aggregation-period 1000
               :client-options {:host "localhost", :port ~port}})]
     (try
       ~@body
       (finally
         (close-connection ~e)))))

;;;

(defn next-msg [ch]
  (-> ch read-channel (wait-for-result 6000)))

(defn next-non-zero-msg [ch]
  (->> (repeatedly #(next-msg ch))
    (drop-while zero?)
    first))

(deftest test-basic-routing
  (with-router 10000
    (with-endpoint [c1] 10000

      (let [a (subscribe c1 "abc")
            b (subscribe c1 "def")]

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

(def sum-op {:name "sum"})
(def rate-op {:name "rate"})
(def avg-op {:name "moving-average" :options {:period 1000}})

(defn group-by-op [facet & operators]
  {:name "group-by"
   :facet facet
   :operators operators})

(deftest test-basic-operators
  (with-router 10000
    (with-endpoint [c] 10000

      (let [sum (subscribe c {:pattern "abc", :operators [sum-op]})
            avg (subscribe c {:pattern "abc", :operators [avg-op]})
            rate (subscribe c {:pattern "abc", :operators [rate-op]})
            sum-avg (subscribe c {:pattern "abc", :operators [sum-op avg-op]})]

        (Thread/sleep 500)

        (doseq [x (range 1 5)]
          (is (= true (trace :abc x))))

        (is (= 4 (next-non-zero-msg rate)))
        (is (= 10 (next-non-zero-msg sum)))
        (is (= 2.5 (next-non-zero-msg avg)))
        (is (= 10.0 (next-non-zero-msg sum-avg)))

        (Thread/sleep 1000)

        (doseq [x (range 6 10)]
          (is (= true (trace :abc x))))

        (is (= 30 (next-non-zero-msg sum)))))))

(deftest test-group-by
  (with-router 10000
    (with-endpoint [c] 10000

      (is (= false (trace :abc 1)))
      
      (let [foo-rate (subscribe c
                       {:pattern "abc"
                        :operators [(group-by-op :foo rate-op)]})
            bar-rate (subscribe c
                       {:pattern "abc"
                        :operators [(group-by-op :bar rate-op)]})
            foo-bar-rate (subscribe c
                           {:pattern "abc"
                            :operators [(group-by-op :foo
                                          (group-by-op :bar rate-op))]})
            val (fn [foo bar] {:foo foo, :bar bar})]

        (Thread/sleep 500)

        (doseq [x (map val [:a :a :b :b :c] [:x :y :z :x :y])]
          (is (= true (trace :abc x))))

        (is (= {:a 2, :b 2, :c 1}
              (next-msg foo-rate)))
        (is (= {:x 2, :y 2, :z 1}
              (next-msg bar-rate)))
        (is (= {:c {:y 1}, :b {:x 1, :z 1}, :a {:y 1, :x 1}}
              (next-msg foo-bar-rate)))))

    (Thread/sleep 500)

    (is (= false (trace :abc {:foo 1, :bar 1})))))
