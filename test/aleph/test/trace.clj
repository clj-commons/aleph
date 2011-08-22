;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.trace
  (:use
    clojure.test
    aleph.trace
    lamina.trace
    lamina.core))

(def options {:host "localhost"})
 
(def interval 100)

#_(deftest test-trace
  (reset-probes options)
  (let [stop-forwarding (forward-probes options)]
    (try
      
      (is (= false (trace :foo 1)))

      (let [ch (consume-probe :foo options)]
	(Thread/sleep interval)
	(is (= true (trace :foo 2)))
	(Thread/sleep interval)
	(close ch)
	(Thread/sleep interval)
	(is (= false (trace :foo 3)))
	(is (= [2] (channel-seq ch))))

      (let [a (consume-probe :bar options)
	    b (consume-probe :bar options)]
	(Thread/sleep interval)
	(is (= true (trace :bar 1)))
	(Thread/sleep interval)
	(close b)
	(is (= true (trace :bar 2)))
	(Thread/sleep interval)
	(close a)
	(Thread/sleep interval)
	(is (= false (trace :bar 3)))
	(let [c (consume-probe :bar options)]
	  (Thread/sleep interval)
	  (is (= true (trace :bar 4)))
	  (Thread/sleep interval)
	  (close c)
	  (Thread/sleep interval)
	  (is (= false (trace :bar 5)))
	  (is (= [4] (channel-seq c))))
	(is (= [1] (channel-seq b)))
	(is (= [1 2] (channel-seq a))))

      (let [ch (consume-probe "foo.{:a 1}" options)]
	(Thread/sleep interval)
	(trace :foo {:a 2, :b 2})
	(trace :foo {:a 1, :b 1})
	(trace :foo {:b 3})
	(Thread/sleep interval)
	(close ch)
	(Thread/sleep interval)
	(is (= [{:a 1, :b 1}] (channel-seq ch))))


      (finally
	(stop-forwarding)))))
