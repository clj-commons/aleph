;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.operators
  (:use
    [lamina core trace]
    [aleph.trace.core])
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [lamina.trace.context]
    [lamina.stats])
  (:import
    [java.util.regex
     Pattern]))

;;;

(defn group-by? [{:strs [name] :as op}]
  (when op
    (= "group-by" name)))

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
    (if (and (group-by? op) (seq (op "operators")))
     (update-in op ["operators"] #(vector (last-operation %)))
     op)))

(defn endpoint-chain
  "Take from beginning of operator chain, taking all that can be
   performed in the endpoint."
  [ops]
  (loop [acc [], ops ops]
    (if (empty? ops)
      acc
      (let [{:strs [operators name] :as op} (first ops)]
        (if (group-by? op)
          (let [operators* (endpoint-chain operators)]
            (if (= operators operators*)
              (recur (conj acc op) (rest ops))
              (conj acc (assoc op "operators" operators*))))
          (if (or
                (not (operator name))
                (endpoint? (operator name)))
            (recur (conj acc op) (rest ops))
            (conj acc op)))))))

(defn aggregator-chain
  "Drop from beginning of operator chain, getting rid of all that can
   be performed in the endpoint, leaving the last for post-split aggregation."
  [ops]
  (if (empty? ops)
    []
    (let [original-ops ops]
      (loop [ops ops]
        (if (empty? ops)
          [(last-operation original-ops)]
          (let [{:strs [operators name] :as op} (first ops)]
            (if (group-by? op)
              (let [operators* (aggregator-chain operators)]
                (if (= operators operators*)
                  (recur (rest ops))
                  (cons (assoc op "operators" operators*) (rest ops))))
              (if (or
                    (not (operator name))
                    (endpoint? (operator name)))
                (recur (rest ops))
                ops))))))))

(defn intra-op [ops]
  (-> ops aggregator-chain first))

;;;

(defn invalid-operators [ops]
  (->> ops operator-seq (map #(get % "name")) (remove operator) seq))

(defn assert-valid-operators [ops]
  (if-let [invalid (invalid-operators ops)]
    (throw (IllegalArgumentException. (str "Don't recognize: " (->> invalid (interpose ", ") (apply str)))))
    true))

(defn endpoint-chain-transform [ops ch]
  (log/info "end-chain" (pr-str ops))
  (assert-valid-operators ops)
  (if-let [{:strs [name] :as op} (last ops)]
    (pre-split (operator name) op
      (reduce
        (fn [ch {:strs [name] :as desc}]
          (endpoint (operator name) desc ch))
        ch
        (butlast ops)))
    ch))

(defn aggregator-chain-transform [ops ch]
  (log/info "ag-chain" (pr-str ops))
  (assert-valid-operators ops)
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

;;; lookups

(defn keywordize [m]
  (zipmap
    (map keyword (keys m))
    (vals m)))

(defn getter [lookup]
  (if (coll? lookup)

    ;; do tuple lookup
    (let [fs (map getter lookup)]
      (fn [m]
        (vec (map #(% m) fs))))

    (let [str-facet (-> lookup name)]
      (cond
        (= "_" str-facet)
        identity
        
        (= "_origin" str-facet)
        (fn [m]
          (origin))
        
        ;; do path lookup
        (re-find #"\." str-facet)
        (let [fields (map getter (str/split str-facet #"\."))]
          (fn [m]
            (reduce
              (fn [m f]
                (when (map? m)
                  (f m)))
              m
              fields)))
        
        ;; do normal lookup
        :else
        (let [key-facet (keyword str-facet)]
          (fn [m]
            (if (contains? m str-facet)
              (get m str-facet)
              (get m key-facet))))))))

(defn selector [m]
  (let [ignore-key? #(re-find #"^[0-9]+" %)
        ks (map (fn [[k v]] (if (ignore-key? k) v k)) m)
        vs (->> m vals (map getter))]
    (assert (every? #(not (re-find #"\." %)) ks))
    (fn [m]
      (zipmap
        ks
        (map #(% m) vs)))))

(defoperator lookup
  :periodic? false

  (:endpoint :aggregator)
  (fn [{:strs [options]} ch]
    (map* (getter (get options "field")) ch)))

(defoperator select
  :periodic? false

  (:endpoint :aggregator)
  (fn [{:strs [options]} ch]
    (map* (selector options) ch)))


;;; where

(defn comparison-filter [[a comparison b]]
  (assert (and a comparison b))
  (let [a (getter a)]
    (case comparison
      "=" #(= (a %) b)
      "<" #(< (a %) b)
      ">" #(> (a %) b)
      "~=" (let [b (-> b (str/replace "*" ".*") Pattern/compile)]
             #(boolean (re-find b (str (a %))))))))

(defn filters [filters]
  (fn [x] (->> filters (map #(% x)) (every? identity))))

(defoperator where
  :periodic? false

  (:endpoint :aggregator)
  (fn [{:strs [options]} ch]
    (filter* (filters (->> options vals (map comparison-filter))) ch)))

;;; group-by

(defn group-by-op [chain-transform {:strs [options operators]} ch]
  ;; handle both keywords and strings
  (let [operators (if (periodic-chain? operators)
                    operators
                    (concat operators [{"name" "partition-every"}]))
        expiration (get options "expiration" (* 1000 30))
        facet (or
                (get options "facet")
                (get options "0"))]
    (assert facet)
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
  (:intra-split :post-split) merge-group-by
  :aggregator (partial group-by-op aggregator-chain-transform))

;;;

(defn sum-op [{:strs [options]} ch]
  (lamina.stats/sum (keywordize options) ch))

(defoperator sum
  :periodic? true
  (:endpoint :aggregator :intra-split) sum-op)

(defn rate-op [{:strs [options]} ch]
  (lamina.stats/rate (keywordize options) ch))

(defoperator rate
  :periodic? true
  (:endpoint :aggregator :intra-split) rate-op
  :post-split sum-op)

(defoperator moving-average
  :periodic? true
  :aggregator (fn [{:strs [options]} ch]
                (lamina.stats/moving-average (keywordize options) ch)))

;;;

(defoperator sample
  :periodic? true
  (:endpoint :aggregator) (fn [{:strs [options]} ch]
                            (let [period (or
                                           (get options "period")
                                           (get options "0")
                                           1000)]
                              (sample-every period ch))))

(defoperator partition-every
  :periodic? true
  (:endpoint :aggregator) (fn [{:strs [options]} ch]
                            (let [period (or
                                           (get options "period")
                                           (get options "0")
                                           1000)]
                              (partition-every period ch))))













