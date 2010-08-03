;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the

;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.pipeline
  (:use [aleph.core] :reload-all)
  (:use [clojure.test])
  (:import [java.util.concurrent
	    TimeoutException
	    CountDownLatch
	    TimeUnit]))

(defn test-pipeline [pipeline expected-result]
  (is (= expected-result (wait-for-pipeline (pipeline 0) 5000))))

(def slow-inc
  (blocking
    (fn [x]
      (Thread/sleep 10)
      (inc x))))

(defn assert-failure [pipeline]
  (try
    (wait-for-pipeline (pipeline 0) 100)
    (catch TimeoutException e
      (is false))
    (catch Exception e
      (is true))))

(defn fail [_]
  (throw (Exception. "boom")))

(def slow-fail
  (blocking (fn [_] (fail))))

(defn fail-times [n]
  (let [counter (atom 3)]
    (fn [x]
      (swap! counter dec)
      (if (pos? @counter)
	(fail nil)
	x))))

(declare pipe-a)
(def pipe-b (pipeline inc #(if (< % 10) (redirect pipe-a %) %)))
(def pipe-a (pipeline inc #(if (< % 10) (redirect pipe-b %) %)))

;;;

(deftest test-pipelines
  (testing "Basic pipelines"
    (test-pipeline (apply pipeline (take 1e3 (repeat inc))) 1e3)
    (test-pipeline (apply pipeline (take 1e3 (repeat (blocking inc)))) 1e3)
    (test-pipeline (apply pipeline (take 100 (repeat slow-inc))) 100))
  (testing "Nested pipelines"
    (test-pipeline (pipeline inc (pipeline inc (pipeline inc) inc) inc) 5))
  (testing "Redirected pipelines"
    (test-pipeline (pipeline inc inc #(redirect (pipeline inc inc inc) %)) 5)
    (test-pipeline (pipeline inc #(if (< % 10) (restart %) %) inc) 11)
    (test-pipeline pipe-b 10))
  (testing "Error propagation"
    (assert-failure (pipeline fail))
    (assert-failure (pipeline inc fail))
    (assert-failure (pipeline inc fail inc))
    (assert-failure (pipeline slow-inc slow-fail))
    (assert-failure (pipeline inc (pipeline inc fail) inc))
    (assert-failure (pipeline inc #(redirect (pipeline inc fail) %))))
  (testing "Error handling"
    (test-pipeline
      (pipeline :error-handler (fn [val ex] (redirect (pipeline inc) val))
	inc
	fail)
      2)
    (test-pipeline
      (pipeline :error-handler (fn [val ex] (restart val))
	inc
	(fail-times 3)
	inc)
      4)
    (test-pipeline
      (pipeline :error-handler (fn [val ex] (restart))
	inc
	(fail-times 3)
	inc)
      2)
    (test-pipeline
      (pipeline
	inc
	(pipeline :error-handler (fn [val ex] (restart val))
	inc
	(fail-times 3))
	inc)
      5)))
