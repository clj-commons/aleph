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
    [aleph.stomp.core]
    [lamina core])
  (:require
    [clojure.tools.logging :as log]
    [lamina.cache :as c]))

(defprotocol+ Router
  (register-publisher [_ endpoint ch])
  (register-subscriber [_ ch]))

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
              (when-let [{endpoint-destination :destination, transform :transform}
                         (aggregator endpoint destination)]

                (let [in (endpoint-generator endpoint-destination)
                      out (generator destination)]

                  ;; forward transformed messages
                  (join
                    (->> in send-messages transform)
                    out)

                  ;; forward errors
                  (join
                    (->> in error-messages)
                    out)))))
          
          ;; resend all active subscriptions
          (doseq [[_ destination] (subscriptions cache)]
            (enqueue local-broadcaster destination))
          
          ;; handle incoming messages and errors
          (receive-all
            (filter*
              (fn [{:keys [command]}]
                (case command
                  (:send :error) true
                  false))
              ch)
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

