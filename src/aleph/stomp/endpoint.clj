;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.endpoint
  (:use
    [potemkin]
    [lamina core connections]
    [aleph.stomp.core])
  (:require
    [clojure.tools.logging :as log]
    [lamina.cache :as c]))

(defprotocol+ Endpoint
  (subscribe [_ destination])
  (publish [_ msg]))

(defn endpoint
  [{:keys [name
           connection-generator
           producer
           query-handler
           message-post-processor
           error-post-processor
           error-encoder
           destination-encoder]
    :or {name "stomp-endpoint"
         destination-encoder identity
         error-encoder #(.getMessage ^Throwable %)
         error-post-processor identity
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

                              ;; resend subscriptions
                              (doseq [[id destination] (subscriptions subscribers)]
                                (enqueue ch (subscription destination id)))

                              ;; handle queries
                              (let [in (->> ch fork query-messages)
                                    out (channel)]
                                (join
                                  (map* #(stomp-message :response %) out)
                                  ch)
                                (server
                                  (fn [ch q]
                                    (enqueue ch (query-handler q)))
                                  (splice in out)
                                  {}))

                              ;; handle incoming messages
                              (receive-all ch
                                (fn [msg]

                                  (let [dest (destination msg)]
                                    (try
                                      (case (:command msg)
                                        
                                        :send
                                        (let [result (enqueue (subscriber dest) msg)]
                                          (when (= :lamina/grounded result)
                                            (release subscribers dest)))
                                        
                                        :subscribe
                                        (when-let [p (and producer (producer dest))]

                                          ;; error forwarding
                                          (on-error p
                                            #(->> %
                                               error-encoder
                                               (error-message destination)
                                               (enqueue ch)))

                                          ;; message forwarding
                                          (let [bridge (channel)]
                                            (swap! active-publishers assoc (id msg) bridge)
                                            (siphon p bridge ch)))
                                        
                                        :unsubscribe
                                        (when producer
                                          (let [id (id msg)]
                                            (when-let [ch (@active-publishers id)]
                                              (close ch)
                                              (swap! active-publishers dissoc id)))))

                                      (catch Exception e
                                        (->> e
                                          error-encoder
                                          (error-message destination)
                                          (enqueue ch)))))))
                              
                              (closed-result ch))

        connection (persistent-connection connection-generator
                     {:name name
                      :on-connected connection-callback})

        ;; don't be lazy about connecting
        _ (connection) 
        ]

    (with-meta
      (reify Endpoint
        (subscribe [_ destination]
          (let [ch (channel)
                destination (destination-encoder destination)]
            (join (subscriber destination) ch)
            (let [msgs (-> ch send-messages message-post-processor)
                  errs (-> ch error-messages error-post-processor)]
              (on-closed msgs #(close errs))
              (on-closed errs #(close msgs))
              {:messages msgs, :errors errs})))
        (publish [_ msg]
          (when-let [conn (connection)]
            (enqueue conn msg))))
      (meta connection))))
