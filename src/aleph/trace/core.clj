;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.core
  (:require [lamina.trace.context])
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
  (when name
    (.get operators name)))

;;;

(defn unwrap-key-vals [keys-val-seq]
  (->> keys-val-seq
    (mapcat
      (fn [[k v]]
        (if-not (coll? k)
          [[k v]]
          (map vector k (repeat v)))))
    (apply concat)
    (apply hash-map)))

(defprotocol TraceOperator
  (periodic? [_])
  (endpoint? [_])
  (intra-split? [_])
  (endpoint [_ desc ch])
  (pre-split [_ desc ch])
  (intra-split [_ desc ch])
  (post-split [_ desc ch])
  (aggregator [_ desc ch]))

(defmacro defoperator [name &
                       {:as args}]
  (let [{:keys [endpoint
                pre-split
                post-split
                intra-split
                aggregator
                periodic?]} (unwrap-key-vals args)]
    `(let [endpoint# ~endpoint
           aggregator# ~aggregator
           pre-split# ~pre-split
           intra-split# ~intra-split
           post-split# ~post-split
           periodic# ~periodic?
           op# (reify
                 TraceOperator
                 (endpoint? [_]
                   ~(boolean endpoint))
                 (intra-split? [_]
                   ~(boolean intra-split))
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
                 (intra-split [_ desc# ch#]
                   (intra-split# desc# ch#))
                 (post-split [_ desc# ch#]
                   (if post-split#
                     (post-split# desc# ch#)
                     (aggregator# desc# ch#)))
                 (aggregator [_ desc# ch#]
                   (aggregator# desc# ch#)))]
       
       (when-not (nil? (.putIfAbsent operators ~(str name) op#))
         (throw (IllegalArgumentException. (str "An operator for '" ~(str name) "' already exists."))))

       op#)))
