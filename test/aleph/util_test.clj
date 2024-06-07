(ns aleph.util-test
  (:require [aleph.util :as util]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as d]))

(deftest test-propagate-error
  (testing "Happy path"
    (let [src (d/deferred)
          dst (d/deferred)
          prp (promise)]
      (util/propagate-error src dst (fn [e] (deliver prp e)))
      (d/error! src :boom)
      (is (d/realized? dst))
      (is (= :boom (d/error-value dst nil)))
      (is (= :boom (deref prp 0 nil)))))

  (testing "Without on-propagate"
    (let [src (d/deferred)
          dst (d/deferred)]
      (util/propagate-error src dst)
      (d/error! src :boom)
      (is (d/realized? dst))))

  (testing "Exception in on-propagate"
    (let [src (d/deferred)
          dst (d/deferred)]
      (util/propagate-error src dst (fn [_] (throw (RuntimeException. "Oops"))))
      (d/error! src :boom)
      (is (d/realized? dst))
      (is (= :boom (d/error-value dst nil)))))

  (testing "Already realized destination"
    (let [src (d/deferred)
          dst (d/success-deferred :ok)]
      (util/propagate-error src dst)
      (d/error! src :boom)
      (is (d/realized? dst))
      (is (= nil (d/error-value dst nil)))))

  (testing "Successfully realized source"
    (let [src (d/deferred)
          dst (d/deferred)]
      (util/propagate-error src dst)
      (d/success! src :ok)
      (is (not (d/realized? dst))))))
