;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.router
  (:use
    [aleph.stomp.core :only (stomp-message)]
    [lamina core trace])
  (:require
    [aleph.trace.core :as trace]
    [aleph.trace.parse :as parse]
    [clojure.tools.logging :as log]
    [aleph.trace.operators :as ops]
    [aleph.stomp :as stomp]
    [aleph.formats :as formats]))

;;; endpoint

(defn wrap-value [command destination val]
  (-> (stomp-message command val)
    (assoc-in [:headers "destination"] (formats/encode-json->string destination))
    (assoc-in [:headers "origin"] (trace/origin))))

;;;

(defn create-probe [{:strs [pattern operators] :as destination}]
  (->> pattern
    select-probes
    (ops/endpoint-chain-transform operators)))

(defn post-endpoint [{:strs [operators] :as destination} period ch]
  (map*
    (partial wrap-value :send destination)
    (if-not (ops/periodic-chain? operators)
      (->> ch (partition-every period) (remove* empty?))
      (->> ch (map* vector)))))

(defn pre-aggregator [{:strs [operators] :as destination} ch]
  (->> ch
    (map* :body)
    (map* read-string)
    concat*))

(defn post-aggregator [destination ch]
  (->> ch
    (map* vector)
    (map* (partial wrap-value :send destination))))

(defn aggregator [endpoint {:strs [operators] :as destination}]
  {:destination (update-in destination ["operators"] ops/endpoint-chain)
   :transform (fn [ch]
                (if-let [ops (-> operators ops/aggregator-chain seq)]
                  (->> ch
                    (pre-aggregator destination)
                    (ops/aggregator-chain-transform ops)
                    (post-aggregator destination))
                  ch))})

;;;

(def router-options
  {:aggregator
   (fn [endpoint destination]
     (update-in (aggregator endpoint (formats/decode-json destination false))
       [:destination]
       formats/encode-json->string))})

(defn endpoint-options [aggregation-period]
  {:producer
   (fn [destination]
     (let [destination (formats/decode-json destination false)]
       (if-let [invalid (ops/invalid-operators (:operators destination))]
         (do
           ;; invalid destination
           )
         (->> destination
           create-probe
           (post-endpoint destination aggregation-period)))))

   :query-handler
   (constantly nil)

   :message-post-processor
   (fn [ch]
     (->> ch
       (map* :body)
       (map* read-string)
       concat*))

   :destination-encoder
   (fn [x]
     (formats/encode-json->string
       (if (string? x)
         (parse/parse-stream x)
         x)))})

