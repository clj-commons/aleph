;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.operators
  (:use
    [lamina core trace])
  (:require
    [clojure.tools.logging :as log]
    [lamina.trace.context]
    [lamina.stats])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

;;;

(def default-origin
  {:host lamina.trace.context/host
   :pid lamina.trace.context/pid})

(def origin-builder (atom nil))

(defn register-origin-builder [f]
  (reset! origin-builder f))

(defn origin []
  (if-let [builder @origin-builder]
    (builder default-origin)
    default-origin))

;;;

(def ^ConcurrentHashMap operators (ConcurrentHashMap.))

(defn operator [name]
  (.get operators name))

(defn group-by? [{:strs [name] :as op}]
  (when op
    (= "group-by" name)))

(defn valid-operators? [operators]
  (or (empty? operators)
    (and
      (->> operators (map #(get % "name")) (every? operator))
      (->> operators (filter group-by?) (mapcat #(get % "operators")) valid-operators?))))

;;;

(defprotocol TraceOperator
  (periodic? [_])
  (endpoint? [_])
  (endpoint [_ desc ch])
  (pre-split [_ desc ch])
  (post-split [_ desc ch])
  (aggregator [_ desc ch]))

(defmacro defoperator [name &
                       {:keys [endpoint
                               pre-split
                               post-split
                               aggregator
                               periodic?]}]
  `(let [endpoint# ~endpoint
         aggregator# ~aggregator
         pre-split# ~pre-split
         post-split# ~post-split
         periodic# ~periodic?
         op# (reify
               TraceOperator
               (endpoint? [_]
                 ~(boolean endpoint))
               (periodic? [_]
                 ~periodic?)
               (endpoint [_ desc# ch#]
                 (endpoint# desc# ch#))
               (pre-split [_ desc# ch#]
                 (cond
                   pre-split#
                   (pre-split# desc# ch#)

                   endpoint#
                   (endpoint# desc# ch#)

                   :else
                   ch#))
               (post-split [_ desc# ch#]
                 (if post-split#
                   (post-split# desc# ch#)
                   (aggregator# desc# ch#)))
               (aggregator [_ desc# ch#]
                 (aggregator# desc# ch#)))]
     
     (when-not (nil? (.putIfAbsent operators ~(str name) op#))
       (throw (IllegalArgumentException. (str "An operator for '" ~(str name) "' already exists."))))

     op#))

;;;

(defn operator-seq [ops]
  (->> ops
    (map
      (fn [{:strs [operators] :as op}]
        (if (group-by? op)
          (cons op (operator-seq operators))
          [op])))
    (apply concat)))

(defn last-operation [ops]
  (let [op (last ops)]
    (if (group-by? op)
     (update-in op ["operators"] #(vector (last-operation %)))
     op)))

(defn endpoint-chain [ops]
  (loop [acc [], ops ops]
    (if (empty? ops)
      acc
      (let [{:strs [operators name] :as op} (first ops)]
        (if (group-by? op)
          (let [operators* (endpoint-chain operators)]
            (if (= operators operators*)
              (recur (conj acc op) (rest ops))
              (conj acc (assoc op "operators" operators*))))
          (if (endpoint? (operator name))
            (recur (conj acc op) (rest ops))
            (conj acc op)))))))

(defn aggregator-chain [ops*]
  (loop [ops ops*]
    (if (empty? ops)
      [(last-operation ops*)]
      (let [{:strs [operators name] :as op} (first ops)]
        (if (group-by? op)
          (let [operators* (aggregator-chain operators)]
            (if (= operators operators*)
              (recur (rest ops))
              (cons (assoc op "operators" operators*) (rest ops))))
          (if (endpoint? (operator name))
            (recur (rest ops))
            ops))))))

;;;

(defn endpoint-chain-transform [ops ch]
  (if-let [{:strs [name] :as op} (last ops)]
    (pre-split (operator name) op
      (reduce
        (fn [ch {:strs [name] :as desc}]
          (endpoint (operator name) desc ch))
        ch
        (butlast ops)))
    ch))

(defn aggregator-chain-transform [ops ch]
  (if-let [{:strs [name] :as op} (first ops)]
    (post-split (operator name) op
      (reduce
        (fn [ch {:strs [name] :as desc}]
          (aggregator (operator name) desc ch))
        ch
        (rest ops)))
    ch))

(defn periodic-chain? [ops]
  (->> ops
    operator-seq
    (map #(get % "name"))
    (map operator)
    (some periodic?)
    boolean))

;;;

(defn keywordize [m]
  (zipmap
    (map keyword (keys m))
    (vals m)))

(defn getter [lookup]
  (if (coll? lookup)
    (let [fs (map getter lookup)]
      (fn [m]
        (vec (map #(% m) fs))))
    (let [str-facet (-> lookup name)
          key-facet (keyword str-facet)]
      (fn [m]
        (if (contains? m str-facet)
          (get m str-facet)
          (get m key-facet))))))

(defn selector [keys]
  (let [getters (map getter keys)]
    (fn [m]
      (zipmap
        keys
        (map #(% m) getters)))))

(defoperator lookup
  :periodic? false
  :endpoint (fn [{:strs [options]} ch]
              (map* (getter (get options "field")) ch))
  :aggregator (fn [{:strs [options]} ch]
                (map* (getter (get options "field")) ch)))

(defoperator select
  :periodic? false
  :endpoint (fn [{:strs [options]} ch]
              (map* (selector (vals options)) ch))
  :aggregator (fn [{:strs [options]} ch]
                (map* (selector (vals options)) ch)))
 

;;; group-by

(defn group-by-op [chain-transform {:strs [options operators]} ch]
  ;; handle both keywords and strings
  (let [expiration (get options "expiration" (* 1000 30))
        facet (or
                (get options "facet")
                (get options "0"))]
    (distribute-aggregate
      (getter facet)
      (fn [k ch]
        (->> ch
          (close-on-idle expiration)
          (chain-transform operators)))
      ch)))

(defn merge-group-by [{:strs [options operators]} ch]
  (let [expiration (get options "expiration" (* 1000 30))]
    (->> ch
      concat*
      (distribute-aggregate first
        (fn [k ch]
          (->> ch
            (close-on-idle expiration)
            (map* second)
            (aggregator-chain-transform operators)))))))

(defoperator group-by
  :periodic? true
  :endpoint (partial group-by-op endpoint-chain-transform)
  :post-split merge-group-by
  :aggregator (partial group-by-op aggregator-chain-transform))

;;;

(defn sum-op [{:strs [options]} ch]
  (lamina.stats/sum (keywordize options) ch))

(defoperator sum
  :periodic? true
  :endpoint sum-op
  :aggregator sum-op)

(defn rate-op [{:strs [options]} ch]
  (lamina.stats/rate (keywordize options) ch))

(defoperator rate
  :periodic? true
  :endpoint rate-op
  :post-split sum-op
  :aggregator rate-op)

(defoperator moving-average
  :periodic? true
  :aggregator (fn [{:strs [options]} ch]
                (lamina.stats/moving-average (keywordize options) ch)))



;;;













