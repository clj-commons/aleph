;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.channel
  (:use [aleph.core.channel])
  (:use
    [clojure.test]
    [clojure.contrib.def]
    [clojure.contrib.combinatorics]))

(defn head [ch]
  (let [val (promise)]
    (listen ch #(do (deliver val %) false))
    @val))

(defvar a nil)
(defvar f nil)
(defvar ch nil)
(defvar enqueue-fn nil)



(defmacro run-test [channel & body]
  `(let [enqueue-count# (atom 0)]
     (binding [a (atom [])
	       ch ~channel
	       f (fn [x#] (swap! a conj x#) true)
	       enqueue-fn #(do
			     (swap! enqueue-count# inc)
			     (if (= 3 @enqueue-count#)
			       (enqueue-and-close ch %)
			       (enqueue ch %)))]
      ~@body
      (deref a))))

(defn try-all [f exprs]
  (let [fns (map
	      (fn [expr] (eval `(fn [] ~expr)))
	      exprs)]
    (map
      (fn [s]
	(f
	  (run-test
	    (channel)
	    (doseq [x s] (x))))
	(f
	  (run-test
	    (let [ch (channel)] (splice ch ch))
	    (doseq [x s] (x)))))
      (permutations fns))))

(defn expected-result [s]
  (loop [s s, enqueued [], result [], consumed false]
    (cond
      (empty? s)
        result
      (nil? (first s))
        (recur
	  (next s)
	  (vec (next enqueued))
	  (conj result (or (first enqueued) (first (filter identity s))))
	  (not (first enqueued)))
      :else
	(recur
	  (next s)
	  (if consumed
	    enqueued
	    (conj enqueued (first s)))
	  result
	  false))))

(deftest test-receive-all
  (try-all
    #(is (= (range 3) %))
    (list*
      `(receive-all ch f)
      (map
	(fn [x] `(enqueue-fn ~x))
	(range 3)))))

(deftest test-receive
  (doall
    (map
      #(is (= %1 %2))
      (map expected-result (permutations [nil nil nil 0 1 2]))
      (try-all
	identity
	(concat
	  (map
	    (fn [_] `(receive ch (fn [x#] (f x#))))
	    (range 3))
	  (map
	    (fn [x] `(enqueue-fn ~x))
	    (range 3)))))))

(deftest test-listen
  (doall
    (map
      #(is (= %1 %2))
      (map expected-result (permutations [nil nil nil 0 1 2]))
      (try-all
	identity
	(concat
	  (map
	    (fn [_] `(listen ch (constantly f)))
	    (range 3))
	  (map
	    (fn [x] `(enqueue-fn ~x))
	    (range 3)))))))

(deftest test-poll
  (let [u (channel)
	v (channel)]
    (let [colls {:u (atom #{})
		 :v (atom #{})}]
      (future
	(doseq [i (range 100)]
	  (Thread/sleep 0 1)
	  (enqueue u i)))
      (future
	(doseq [i (range 100)]
	  (Thread/sleep 0 1)
	  (enqueue v i)))
      (doseq [i (range 200)]
	(let [[ch msg] (wait-for-message (poll {:u u, :v v}))]
	  (swap! (colls ch) conj msg)))
      (is (= (set (range 100)) @(:u colls)))
      (is (= (set (range 100)) @(:v colls))))))

(deftest test-poll-timeout
  (let [ch (channel)]
    (is (= nil (wait-for-message (poll {:ch ch} 0) 0)))))

(deftest test-wait-for-message
  (let [num 1e3]
    (let [ch (channel)]
      (future
	(dotimes [i num]
	  (if (= i (dec num))
	    (enqueue-and-close ch i)
	    (enqueue ch i))
	  (Thread/sleep 0 1)))
      (dotimes [i num]
	(is (= i (wait-for-message ch)))))))

(deftest test-channel-seq
  (let [ch (channel)
        in (take 100 (iterate inc 0))
        out (do (future
                 (doseq [i in]
                   (enqueue ch i)
                   (Thread/sleep 1)))
                (loop [out []]
                  (Thread/sleep 3)
                  (let [n (channel-seq ch)]
                    (if (seq n)
                      (recur (concat out n))
                      out))))]
    (is (= in out))))
