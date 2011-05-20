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

(deftype LazyMap [m]
  clojure.lang.IPersistentCollection
  (equiv [this x]
    (and
      (map? x)
      (= x (into {} this))))
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
    (lazy-get this k))
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
    (LazyMap. (atom (dissoc @m k)))))

(defn lazy-get [^LazyMap m k]
  (let [m-val @(.m m)
	v (get m-val k)]
    (if (delayed? v)
      (-> (.m m)
	(swap! #(-> % (dissoc k) (merge (or (v m) {k nil}))))
	(get k))
      v)))

(defn lazy-map [& key-values]
  (LazyMap. (atom (apply hash-map key-values))))

(defmethod print-method LazyMap [m writer]
  (.write writer (str m)))
