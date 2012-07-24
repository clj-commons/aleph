;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.router
  (:use
    [lamina core trace connections])
  (:require
    [lamina.cache :as c]))

(defprotocol Router
  (register-publisher [_ ch])
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

(defn router
  ([name]
     (router name (fn [dest] {:destination dest, :transform identity})))
  ([name transformer]
     (let [subscriptions (permanent-channel)
           n (atom 0)
           gen-id #(swap! n inc)
           active-subscriptions (atom {})
           cache (c/channel-cache #(channel* :description % :grounded? true))

           generator (fn [destination]
                       (c/get-or-create cache destination
                         (fn [ch]
                           (let [id (gen-id)]

                             ;; add to active subscription list
                             (swap! active-subscriptions
                               assoc id destination)

                             ;; broadcast subscription
                             (enqueue subscriptions
                               (subscription destination id))

                             ;; hook up unsubscription
                             (on-closed ch 
                               (fn []
                                 (swap! active-subscriptions
                                   dissoc id)
                                 (enqueue subscriptions
                                   (unsubscription id))))))))]
       
       (reify Router
      
         (register-publisher [_ ch]

           ;; connect to subscriptions
           (siphon subscriptions ch)

           ;; resend all active subscriptions
           (doseq [[id destination] @active-subscriptions]
             (enqueue subscriptions (subscription destination id)))

           ;; handle all messages
           (receive-all
             (filter* #(= :send (:command %)) ch)
             (fn [msg]
               (enqueue (generator (destination msg)) msg))))

         (register-subscriber [_ ch]
        
           (let [subs (c/channel-cache #(channel* :description %))]
             (receive-all ch
               (fn [msg]
                 (case (:command msg)

                   :subscribe
                   (let [dest (destination msg)
                         {:keys [destination transform]} (transformer dest)]
                     (siphon
                       (map* transform (generator destination))
                       (c/get-or-create subs (id msg) nil)
                       ch))
                  
                   :unsubscribe
                   (c/release subs (id msg))
                  
                   nil)))))))))

;;;

(defprotocol Endpoint
  (subscribe [_ destination])
  (publish [_ msg]))

(defn endpoint [name connection-generator producer]
  (let [conn (atom nil)
        n (atom 0)
        gen-id #(swap! n inc)

        active-subscriptions (atom {})
        subscribers (c/channel-cache #(channel* :description % :grounded? true))
        subscriber (fn [destination]
                     (c/get-or-create subscribers destination
                       (fn [subscriber]
                         (let [id (gen-id)]
                           
                           ;; add to subscriptions
                           (swap! active-subscriptions
                             assoc id destination)
                           
                           ;; send subscription
                           (when-let [conn @conn]
                             (enqueue conn (subscription destination id)))
                           
                           (on-closed subscriber
                             (fn []
                               (swap! active-subscriptions
                                 dissoc id)
                               (when-let [conn @conn]
                                 (enqueue conn (unsubscription id)))))))))

        active-publishers (atom {})
        publishers (c/channel-cache #(channel* :description % :grounded true?))
        publisher #(c/get-or-create publishers destination (producer %))

        connection-callback (fn [ch]

                              (reset! conn ch)
                              (reset! active-publishers {})
                                
                              (doseq [[id destination] @active-subscriptions]
                                (enqueue ch (subscription destination id)))
                                
                              (receive-all ch
                                (fn [msg]
                                  (let [dest (destination msg)]
                                    (case (:command msg)
                                        
                                      :send
                                      (let [result (enqueue (subscriber dest) msg)]
                                        (when (= :lamina/grounded result)
                                          (c/release subscribers dest)))
                                        
                                      :subscribe
                                      (let [bridge (channel)]
                                        (swap! active-publishers assoc (id msg) bridge)
                                        (siphon (producer dest) bridge ch))
                                        
                                      :unsubscribe
                                      (let [id (id msg)]
                                        (close (@active-publishers id))
                                        (swap! active-publishers dissoc id))))))
                                
                              (closed-result ch))

        connection (persistent-connection connection-generator
                     {:name name
                      :on-connected connection-callback})]

    (with-meta
      (reify Endpoint
        (subscribe [_ destination]
          (let [_ (connection)
                ch (channel)]
            (siphon (subscriber destination) ch)
            ch))
        (publish [_ msg]
          (when-let [conn (connection)]
            (enqueue conn msg))))
      (meta connection))))
