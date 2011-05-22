;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.core.lazy-map
  (:use
   [clojure.test]
   [aleph.core.lazy-map :only [delayed lazy-map]]))

(defn- mock-delayed
  [k v]
  (fn [_] {k v}))

(defn- make-lazy-map
  []
  (lazy-map :a 1 :b 2 :c (delayed (mock-delayed :c 3))))

(deftest apply-to-0
  (is (thrown-with-msg? RuntimeException #"Wrong number of args \(0\) passed to: LazyMap"
        (apply (make-lazy-map) []))))

(deftest apply-to-1
  (is (= 3 (apply (make-lazy-map) [:c]))))

(deftest apply-to-2
  (let [m (make-lazy-map)]
    (is (= 3 (apply m [:c 4])))
    (is (= 4 (apply m [:not-in-the-map 4])))))

(deftest apply-to-3
  (is (thrown-with-msg? RuntimeException #"Wrong number of args \(3\) passed to: LazyMap"
        (apply (make-lazy-map) [:one :too :many]))))
