;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.router
  (:use
    [potemkin]
    [lamina core trace connections])
  (:require
    [clojure.tools.logging :as log]
    [lamina.cache :as c]))

(defprotocol Router
  (register-publisher [_ endpoint ch])
  (register-subscriber [_ ch]))

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

(defprotocol-once SubscriptionCache
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

(defn router
  [{:keys [name
           aggregator]
    :or {name "stomp-router"
         aggregator (fn [_ dest]
                      {:destination dest
                       :transform identity})}}]
  (let [subscription-broadcaster (permanent-channel)
        cache (subscription-cache
                {:generator
                 #(channel* :description % :grounded? true)

                 :on-subscribe
                 (fn [destination _]
                   (enqueue subscription-broadcaster destination))})

        generator #(get-or-create cache %)]
       
    (reify Router
      
      (register-publisher [_ endpoint ch]

        (let [local-broadcaster (channel)
              endpoint-cache (subscription-cache
                               {:generator
                                #(channel* :description % :grounded? true)
                                
                                :on-subscribe
                                (fn [destination id]
                                  (enqueue ch
                                    (subscription destination id)))
                                
                                :on-unsubscribe
                                (fn [id]
                                  (enqueue ch
                                    (unsubscription id)))})
              endpoint-generator #(get-or-create endpoint-cache %)]
          
          ;; handle subscriptions
          (siphon subscription-broadcaster local-broadcaster)
          (on-closed ch #(close local-broadcaster))
          (receive-all local-broadcaster
            (fn [destination]
              ;; connect endpoint destination to router destination
              (when-let [{destination* :destination, transform :transform}
                         (aggregator endpoint destination)]
                (join
                  (transform (endpoint-generator destination*))
                  (generator destination)))))
          
          ;; resend all active subscriptions
          (doseq [[_ destination] (subscriptions cache)]
            (enqueue local-broadcaster destination))
          
          ;; handle all messages
          (receive-all
            (filter* #(= :send (:command %)) ch)
            (fn [msg]
              (enqueue (endpoint-generator (destination msg)) msg)))))

      (register-subscriber [_ ch]
        
        (let [subs (c/channel-cache #(channel* :description %))]
          (receive-all ch
            (fn [msg]
              (case (:command msg)

                :subscribe
                (let [dest (destination msg)]
                  (siphon
                    (generator dest)
                    (c/get-or-create subs (id msg) nil)
                    ch))
                  
                :unsubscribe
                (c/release subs (id msg))
                  
                nil))))))))

;;;

(defprotocol-once Endpoint
  (subscribe [_ destination])
  (publish [_ msg]))

(defn endpoint
  [{:keys [name
           connection-generator
           producer
           message-post-processor
           destination-encoder]
    :or {name "stomp-endpoint"
         destination-encoder identity
         message-post-processor identity}}]
  (let [conn (atom nil)
        subscribers (subscription-cache
                      {:generator
                       #(channel* :description % :grounded? true)
                       :on-subscribe
                       (fn [destination id]
                         (when-let [conn @conn]
                           (enqueue conn (subscription destination id))))
                       :on-unsubscribe
                       (fn [id]
                         (when-let [conn @conn]
                           (enqueue conn (unsubscription id))))})

        subscriber #(get-or-create subscribers %)

        active-publishers (atom {})
        publishers (c/channel-cache #(channel* :description % :grounded true?))
        publisher #(c/get-or-create publishers % (producer %))

        connection-callback (fn [ch]

                              (reset! conn ch)
                              (reset! active-publishers {})
                                
                              (doseq [[id destination] (subscriptions subscribers)]
                                (enqueue ch (subscription destination id)))
                                
                              (receive-all ch
                                (fn [msg]
                                  (let [dest (destination msg)]
                                    (case (:command msg)
                                        
                                      :send
                                      (let [result (enqueue (subscriber dest) msg)]
                                        (when (= :lamina/grounded result)
                                          (release subscribers dest)))
                                        
                                      :subscribe
                                      (when producer
                                        (let [bridge (channel)]
                                          (swap! active-publishers assoc (id msg) bridge)
                                          (let [p (producer dest)]
                                            (on-error p #(log/error % (str "Error in endpoint for " dest)))
                                            (siphon p bridge ch))))
                                        
                                      :unsubscribe
                                      (when producer
                                        (let [id (id msg)]
                                          (close (@active-publishers id))
                                          (swap! active-publishers dissoc id)))))))
                                
                              (closed-result ch))

        connection (persistent-connection connection-generator
                     {:name name
                      :on-connected connection-callback})

        _ (connection) ;; don't be lazy about connecting
        ]

    (with-meta
      (reify Endpoint
        (subscribe [_ destination]
          (let [_ (connection)
                ch (channel)
                destination (destination-encoder destination)]
            (join (subscriber destination) ch)
            (message-post-processor ch)))
        (publish [_ msg]
          (when-let [conn (connection)]
            (enqueue conn msg))))
      (meta connection))))
