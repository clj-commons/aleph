;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.operators
  (:use
    [lamina.core])
  (:require
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

(def operators (ConcurrentHashMap.))

(defn operator [name]
  (.get operators name))

(defn group-by? [{:keys [name] :as op}]
  (when op
    (= "group-by" name)))

(defn valid-operators? [operators]
  (or (empty? operators)
    (and
      (->> operators (map :name) (every? operator))
      (->> operators (filter group-by?) (mapcat :operators) valid-operators?))))

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
                 (if pre-split#
                   (pre-split# desc# ch#)
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
      (fn [{:keys [operators] :as op}]
        (if (group-by? op)
          (cons op (operator-seq operators))
          [op])))
    (apply concat)))

(defn first-operation [ops]
  (->> ops
    operator-seq
    (remove group-by?)
    first))

(defn butlast-operation [ops]
  (let [op (last ops)]
    (if (group-by? op)
      (concat (butlast ops) [(butlast-operation op)])
      (butlast ops))))

(defn rest-operations [ops]
  (let [op (first ops)]
    (if (group-by? op)
      (cons (rest-operations op) (rest ops))
      (rest ops))))

(defn last-operation [ops]
  (->> ops
    operator-seq
    last))

(defn endpoint-chain [ops]
  (loop [acc [], ops ops]
    (if (empty? ops)
      acc
      (let [{:keys [operators name] :as op} (first ops)]
        (if (group-by? op)
          (let [operators* (endpoint-chain operators)]
            (if (= operators operators*)
              (recur (conj acc op) (rest ops))
              (conj acc (assoc op :operators operators*))))
          (if (endpoint? (operator name))
            (recur (conj acc op) (rest ops))
            (conj acc op)))))))

(defn aggregator-chain [ops*]
  (loop [ops ops*]
    (if (empty? ops)
      [(last-operation ops*)]
      (let [{:keys [operators name] :as op} (first ops)]
        (if (group-by? op)
          (let [operators* (aggregator-chain operators)]
            (if (= operators operators*)
              (recur (rest ops))
              (cons (assoc op :operators operators*) (rest ops))))
          (if (endpoint? (operator name))
            (recur (rest ops))
            ops))))))

;;;

(defn endpoint-chain-transform [ops ch]
  (if-let [{:keys [name] :as op} (last-operation ops)]
    (pre-split (operator name) op
      (reduce
        (fn [ch {:keys [name] :as desc}]
          (endpoint (operator name) desc ch))
        ch
        (butlast-operation ops)))
    ch))

(defn aggregator-chain-transform [ops ch]
  (if-let [{:keys [name] :as op} (first-operation ops)]
    (post-split (operator name) op
      (reduce
        (fn [ch {:keys [name] :as desc}]
          (aggregator (operator name) desc ch))
        ch
        (rest-operations ops)))
    ch))

(defn periodic-chain? [ops]
  (->> ops
    operator-seq
    (map :name)
    (map operator)
    (some periodic?)
    boolean))

;;;

(defn sum-op [{:keys [options]} ch]
  (lamina.stats/sum options ch))

(defoperator sum
  :periodic? true
  :endpoint (fn [{:keys [options]} ch]
              (->> ch
                (lamina.stats/sum options)
                (remove* zero?)))
  :aggregator sum-op)

(defoperator rate
  :periodic? true
  :endpoint (fn [{:keys [options]} ch]
              (->> ch
                (lamina.stats/rate options)
                (remove* zero?)))
  :post-split sum-op
  :aggregator (fn [{:keys [options]} ch]
                (lamina.stats/rate options ch)))

(defoperator moving-average
  :periodic? true
  :aggregator (fn [{:keys [options]} ch]
                (lamina.stats/moving-average options ch)))



;;;













