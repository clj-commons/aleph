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
    [lamina.stats])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

;;;

(def operators (ConcurrentHashMap.))

(defn operator [name]
  (.get operators name))

(defn valid-operators? [operators]
  (->> operators (map :name) (every? operator)))

;;;

(defprotocol TraceOperator
  (endpoint? [_])
  (aggregator? [_])
  (post-aggregator? [_])
  (endpoint [_ options ch])
  (aggregator [_ options ch])
  (post-aggregator [_ options ch]))

(defmacro defoperator [name &
                       {:keys [endpoint
                               aggregator
                               post-aggregator]}]
  `(let [endpoint# ~endpoint
         aggregator# ~aggregator
         post-aggregator# ~post-aggregator
         op# (reify
               TraceOperator
               (endpoint? [_]
                 ~(boolean endpoint))
               (aggregator? [_]
                 ~(boolean aggregator))
               (post-aggregator? [_]
                 ~(boolean post-aggregator))
               (endpoint [_ options# ch#]
                 (endpoint# options# ch#))
               (aggregator [_ options# ch#]
                 (aggregator# options# ch#))
               (post-aggregator [_ options# ch#]
                 (post-aggregator# options# ch#)))]
     
     (when-not (nil? (.putIfAbsent operators ~(str name) op#))
       (throw (IllegalArgumentException. (str "An operator for '" ~(str name) "' already exists."))))

     op#))

(defoperator sum
  :endpoint lamina.stats/sum
  :aggregator (fn [options ch]
                (->> ch
                  (map* :data)
                  (lamina.stats/sum options)))
  :post-aggregator lamina.stats/sum)

(defoperator rate
  :endpoint lamina.stats/rate
  :aggregator (fn [options ch]
                (->> ch
                  (map* :data)
                  (lamina.stats/sum)))
  :post-aggregator lamina.stats/rate)

(defoperator moving-average
  :aggregator (fn [options ch]
                (->> ch
                  (map* :data)
                  (lamina.stats/moving-average options)))
  :post-aggregator lamina.stats/moving-average)









