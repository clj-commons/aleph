;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.router
  (:use
    [lamina core trace]
    [aleph.trace.operators])
  (:require
    [lamina.trace.context]
    [aleph.stomp :as stomp]
    [aleph.formats :as formats]))

;;; endpoint

(def default-origin
  {:host lamina.trace.context/host
   :pid lamina.trace.context/pid})

(def origin-builder (atom nil))

(defn origin []
  (if-let [builder @origin-builder]
    (builder default-origin)
    default-origin))

(defn wrap-value [destination val]
  (let [body (formats/encode-json->string val)]
    {:command :send
     :headers {:destination destination
               :origin (formats/encode-json->string (origin))
               :content-type "application/json"
               :content-length (count body)}
     :body body}))

;;;

(defn endpoint-wrapper [destination period ch]
  (->> ch
    (partition-every period)
    (remove* empty?)
    (map* (partial wrap-value destination))))

(defn create-probe [{:keys [pattern operators]}]
  (if-not (valid-operators? operators)
    (do
      ;; invalid stream
      )
    (let [probes (select-probes pattern)]
      (reduce
        (fn [ch {:keys [options name]}]
          (endpoint (operator name) options ch))
        probes
        operators))))

;;; aggregator

(defn valid-destination? [{:keys [operators] :as m}]
  (and
    (valid-operators? operators)
    (let [post-endpoint (->> operators
                          (map :name)
                          (map operator)
                          (drop-while endpoint?))]
      (or
        (empty? post-endpoint)
        (and
          (aggregator? (first post-endpoint))
          (every? post-aggregator? (rest post-endpoint)))))))

(defn endpoint-unwrapper [ch]
  (->> ch
    (map* (fn [{:keys [headers body]}]
            (let [origin (formats/decode-json (get headers "origin"))
                  body (formats/decode-json body)]
              (map #(hash-map :origin origin, :data %) body))))
    concat*))

(defn endpoint-processor [endpoint {:keys [operators] :as destination}]
  (if-not (valid-destination? destination)
    (do
      ;; invalid stream
      )
    (let [endpoint-operators (->> operators
                               (map :name)
                               (map operator)
                               (take-while endpoint?))
          aggregator-operators (drop (count endpoint-operators) operators)]

      {:destination (update-in destination [:operators]
                      #(when %
                         (take (count endpoint-operators) %)))
       :transform (fn [ch]
                    (let [ ;; unwrap the bundled messages
                          ch (endpoint-unwrapper ch)

                          ;; handle the origin-laden messages
                          ch (if-let [{:keys [name options]} (first aggregator-operators)]
                               (aggregator (operator name) options ch)
                               (map* :data ch))]

                      ;; handle all subsequent steps
                      (reduce
                        (fn [ch {:keys [options name]}]
                          (post-aggregator (operator name) options ch))
                        ch
                        (rest aggregator-operators))))})))

;;;

(def router-options
  {:endpoint-processor
   (fn [endpoint destination]
     (update-in (endpoint-processor endpoint (formats/decode-json destination))
       [:destination]
       formats/encode-json->string))

   :aggregator-post-processor
   (fn [destination ch]
     (map* #(wrap-value destination %) ch))})

(defn endpoint-options [aggregation-period]
  {:producer
   (fn [destination]
     (endpoint-wrapper
       destination
       aggregation-period
       (create-probe
         (formats/decode-json destination))))

   :message-decoder
   (fn [msg]
     (-> msg :body formats/decode-json))

   :destination-encoder
   (fn [x]
     (formats/encode-json->string
       (if (string? x)
         {:pattern x}
         x)))})

