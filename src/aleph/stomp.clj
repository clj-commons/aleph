;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp
  (:use
    [potemkin]
    [lamina core connections])
  (:require
    [clojure.string :as str]
    [lamina.cache :as cache]
    [aleph.tcp :as tcp]
    [aleph.stomp.core :as stomp]
    [aleph.stomp.codec :as codec]))

;;;

(defn- router-handshake? [handshake]
  (let [x-router (get-in handshake [:headers "X-Router"] "false")]
    (= "true" (str/lower-case x-router))))

(defn- stomp-router-handler [router]
  (fn [ch _]
    (run-pipeline (read-channel ch)
      (fn [{:keys [command] :as handshake}]
        (if (not= :connect command)

          ;; improper handshake, close connection
          (enqueue-and-close ch (stomp/error-message nil "expected CONNECT frame"))

          ;; check if it's a router, handle producer/consumer registration properly
          (let [router? (router-handshake? handshake)]
            (enqueue ch (stomp/stomp-message :connected nil))
            (stomp/register-producer router ch router?)
            (stomp/register-consumer router ch)))))))

(defn start-stomp-router [options]
  (let [name (or
               (:name options)
               (-> options :server :name)
               "stomp-router")
        router (stomp/stomp-router identity)]
    (tcp/start-tcp-server
      (stomp-router-handler router)
      (assoc options
        :name name
        :frame codec/message-codec))))

;;;

(defn stomp-connection [options]
  (tcp/tcp-client (assoc options :frame codec/message-codec)))

(defprotocol+ IStompClient
  (publish [_ destination msg])
  (subscribe [_ destination]))

(defn stomp-client [options]
  (let [conn (persistent-connection
               (fn []
                 (run-pipeline (stomp-connection options)
                   (fn [ch]
                     (enqueue ch (stomp/stomp-message :connect nil))
                     (run-pipeline (read-channel ch)
                       (fn [rsp]

                         ;; todo: check response

                         ch))))))]

    ;; todo: make this persistent, a la redis-stream
    
    (run-pipeline (conn)
      (fn [ch]
        (let [cnt (atom 0)

              destination-channels
              (cache/topic-channel-cache
                {:generator
                 #(channel* :description %, :grounded? true)
                     
                 :topic->id
                 (fn [_] (swap! cnt inc))
                     
                 :on-subscribe
                 (fn [_ destination id]
                   (enqueue ch (stomp/subscription destination id)))
                     
                 :on-unsubscribe
                 (fn [_ destination id]
                   (enqueue ch (stomp/unsubscription id)))})]
              
          ;; forward messages to the right place
          (receive-all ch
            (fn [{:keys [command] :as msg}]
              (case command
                (:error :send)
                (enqueue
                  (cache/get-or-create destination-channels (stomp/message-destination msg) nil)
                  msg))))

          (with-meta
                
            (reify IStompClient
              (publish [_ destination msg]
                (enqueue ch (stomp/send-message destination msg)))
              (subscribe [_ destination]
                (let [combined (cache/get-or-create destination-channels destination nil)
                      messages (filter* #(= :send (:command %)) combined)
                      errors   (filter* #(= :error (:command %)) combined)]
                      
                  ;; hook up various callbacks
                  (on-closed messages #(close errors))
                  (on-closed errors #(close messages))
                  (let [close-callback #(close messages)]
                    (on-closed ch close-callback)
                    (on-closed messages #(cancel-callback ch close-callback)))
                      
                  {:messages messages, :errors errors})))

            {:lamina.connections/close-fn #(close-connection conn)
             :lamina.connections/reset-fn #(reset-connection conn)}))))))
