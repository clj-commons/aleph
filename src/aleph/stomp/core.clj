;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.core
  (:use
    [potemkin]
    [lamina.core])
  (:require
    [lamina.cache :as c]))

(defn destination [msg]
  (get-in msg [:headers "destination"]))

(defn id [msg]
  (get-in msg [:headers "id"]))

(defn subscription [destination id]
  {:command :subscribe
   :headers {"destination" destination
             "id" id
             "ack" "auto"}})

(defn unsubscription [id]
  {:command :unsubscribe
   :headers {"id" id}})

(defprotocol+ SubscriptionCache
  (get-or-create [_ destination])
  (release [_ destination])
  (subscriptions [_]))

;; a channel cache that correlates destination and ids
(defn subscription-cache
  [{:keys [generator
           on-subscribe
           on-unsubscribe]}]
  (let [n (atom 0)
        gen-id #(swap! n inc)
        active-subscriptions (atom {})
        cache (c/channel-cache generator)]
    (reify SubscriptionCache

      (get-or-create [_ destination]
        (c/get-or-create cache destination
          (fn [ch]
            (let [id (gen-id)]
              
              ;; add to active subscription list
              (swap! active-subscriptions
                assoc id destination)
              
              ;; broadcast subscription
              (when on-subscribe
                (on-subscribe destination id))

              ;; hook up unsubscription
              (on-closed ch 
                (fn []
                  (swap! active-subscriptions
                    dissoc id)
                  (when on-unsubscribe
                    (on-unsubscribe id))))))))

      (release [_ destination]
        (c/release cache destination))

      (subscriptions [_]
        @active-subscriptions))))

(defn send-messages [ch]
  (filter* #(= :send (:command %)) ch))

(defn error-messages [ch]
  (filter* #(= :error (:command %)) ch))

(defn query-messages [ch]
  (filter* #(= :query (:command %)) ch))

(defn response-messages [ch]
  (filter* #(= :response (:command %)) ch))

(defn stomp-message [command val]
  (let [body (pr-str val)]
    {:command command
     :headers {:content-type "application/clojure"
               :content-length (count body)}
     :body body}))

(defn error-message [destination val]
  (assoc-in (stomp-message :error)
    [:headers "destination"] destination))
