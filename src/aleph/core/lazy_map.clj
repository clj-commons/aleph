;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.core.lazy-map)

(defmacro delayed [f]
  `(let [f# ~f]
     (with-meta f# (merge (meta f#) {::delayed true}))))

(defn delayed? [x]
  (-> x meta ::delayed))

(declare lazy-get)

(defn- throw-lazy-arity
  [actual]
  (throw (RuntimeException.
          (str "Wrong number of args (" actual ") passed to: LazyMap"))))

(deftype LazyMap [m]
  clojure.lang.IPersistentCollection
  (equiv [this x]
    (and
      (map? x)
      (= x (into {} this))))
  clojure.lang.Counted
  (count [this]
    (count (seq this)))
  clojure.lang.Seqable
  (seq [this]
    (doseq [k (keys @m)]
      (lazy-get this k))
    (seq @m))
  Object
  (toString [this]
    (str (into {} this)))
  clojure.lang.ILookup
  (valAt [this k]
    (.valAt this k nil))
  (valAt [this k default]
    (lazy-get this k default))
  clojure.lang.Associative
  (containsKey [_ k]
    (contains? @m k))
  (entryAt [this k]
    (let [v (lazy-get this k)]
      (reify java.util.Map$Entry
	(getKey [_] k)
	(getValue [_] v))))
  (assoc [this k v]
    (LazyMap. (atom (assoc @m k v))))
  clojure.lang.IPersistentMap
  (assocEx [this k v]
    (let [theMap @m]
      (if (contains? theMap k)
        (throw (Exception. "Key or value already present"))
        (LazyMap. (atom (assoc theMap k v))))))
  (without [this k]
    (LazyMap. (atom (dissoc @m k))))
  java.util.concurrent.Callable
  (call [this]
    (throw-lazy-arity 0))
  java.lang.Runnable
  (run [this]
    (throw-lazy-arity 0))
  clojure.lang.IFn
  (invoke [this]
    (throw-lazy-arity 0))
  (invoke [this k]
    (get this k))
  (invoke [this k not-found]
    (get this k not-found))
  (invoke [this a1 a2 a3]
    (throw-lazy-arity 3))
  (invoke [this a1 a2 a3 a4]
    (throw-lazy-arity 4))
  (invoke [this a1 a2 a3 a4 a5]
    (throw-lazy-arity 5))
  (invoke [this a1 a2 a3 a4 a5 a6]
    (throw-lazy-arity 6))
  (invoke [this a1 a2 a3 a4 a5 a6 a7]
    (throw-lazy-arity 7))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8]
    (throw-lazy-arity 8))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9]
    (throw-lazy-arity 9))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10]
    (throw-lazy-arity 10))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11]
    (throw-lazy-arity 11))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12]
    (throw-lazy-arity 12))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13]
    (throw-lazy-arity 13))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14]
    (throw-lazy-arity 14))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15]
    (throw-lazy-arity 15))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16]
    (throw-lazy-arity 16))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17]
    (throw-lazy-arity 17))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18]
    (throw-lazy-arity 18))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19]
    (throw-lazy-arity 19))
  (invoke [this a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20]
    (throw-lazy-arity 20))
  (applyTo [this args]
    (case (count args)
      1 (.invoke this (first args))
      2 (.invoke this (first args) (second args))
      (throw-lazy-arity (count args)))))

(defn lazy-get
  ([^LazyMap m k]
     (lazy-get m k nil))
  ([^LazyMap m k default]
     (let [m-val @(.m m)
           v (get m-val k ::lazy-map-not-found)]
       (if (= ::lazy-map-not-found v)
         default
         (if (delayed? v)
           (-> (.m m)
               (swap! #(-> % (dissoc k) (merge (or (v m) {k nil}))))
               (get k))
           v)))))

(defn lazy-map [& key-values]
  (LazyMap. (atom (apply hash-map key-values))))

(defmethod print-method LazyMap [m writer]
  (.write writer (str m)))
