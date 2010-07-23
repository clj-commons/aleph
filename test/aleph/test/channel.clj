;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.channel
  (:use [aleph.channel] :reload-all)
  (:use
    [clojure.test]
    [clojure.contrib.def]
    [clojure.contrib.combinatorics]))

(defn head [ch]
  (let [val (promise)]
    (listen ch #(do (deliver val %) false))
    @val))

(defvar a nil)
(defvar ch nil)
(defvar f nil)

(defmacro run-test [& body]
  `(binding [a (atom [])
	     ch (channel)
	     f (fn [x#] (swap! a conj x#) true)]
     ~@body
     (deref a)))

(defn try-all [f exprs]
  (let [fns (map
	      (fn [expr] (eval `(fn [] ~expr)))
	      exprs)]
    (map
      #(f (run-test
	    (doseq [x %]
	      (x))))
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
	(fn [x] `(enqueue ch ~x))
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
	    (fn [x] `(enqueue ch ~x))
	    (range 3)))))))

(deftest test-listen-all
  (try-all
    #(is (= (range 3) %))
    (list*
      `(listen-all ch f)
      (map
	(fn [x] `(enqueue ch ~x))
	(range 3)))))

(deftest test-listen
  (doall
    (map
      #(is (= %1 %2))
      (map expected-result (permutations [nil nil nil 0 1 2]))
      (try-all
	identity
	(concat
	  (map
	    (fn [_] `(listen ch (fn [x#] (f x#))))
	    (range 3))
	  (map
	    (fn [x] `(enqueue ch ~x))
	    (range 3)))))))
