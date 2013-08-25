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
    [clojure.string :as str]
    [aleph.tcp :as tcp]
    [aleph.stomp.codec :as codec]
    [lamina.cache :as c])
  (:import
    [lamina.cache IRouter]))

;;;

(defn message-destination [msg]
  (get-in msg [:headers "destination"]))

(defn message-id [msg]
  (get-in msg [:headers "id"]))

(defn subscription [destination id]
  {:command :subscribe
   :headers {"destination" destination
             "id" id
             "ack" "auto"}})

(defn unsubscription [id]
  {:command :unsubscribe
   :headers {"id" id}})

(defn stomp-message [command val]
  (let [body (when val (pr-str val))]
    {:command command
     :headers {:content-type "application/clojure"
               :content-length (count body)}
     :body body}))

(defn error-message [destination val]
  (assoc-in (stomp-message :error val)
    [:headers "destination"] destination))

(defn send-message [destination val]
  (assoc-in (stomp-message :send val)
    [:headers "destination"] destination))

;;;

(defprotocol+ IStompRouter
  (register-producer [_ ch router?])
  (register-consumer [_ ch]))

(defn id->topic [router]
  (let [inner-cache (c/inner-cache router)
        ids (c/ids inner-cache)]
    (zipmap ids (map #(c/id->topic inner-cache %) ids))))

(defn stomp-router [router-generator]
  (let [message-channels (c/channel-cache #(channel* :description %, :grounded? true))
        subscription-broadcast (channel* :permanent? true, :grounded? true)
        cnt (atom 0)
        inner-router (c/router
                       {:topic->id
                        (fn [_] (swap! cnt inc))

                        :on-subscribe
                        (fn [_ topic id]
                          (enqueue subscription-broadcast (subscription topic id)))

                        :on-unsubscribe
                        (fn [_ topic id]
                          (enqueue subscription-broadcast (unsubscription id)))

                        :generator
                        #(c/get-or-create message-channels % nil)})
        outer-router (router-generator inner-router)]

    (reify
      IStompRouter

      (register-producer [this ch router?]

        (when router?
          (siphon subscription-broadcast ch)
          (doseq [[id topic] (id->topic this)]
            (enqueue ch (subscription topic id))))

        (receive-all ch
          (fn [{:keys [command] :as msg}]
            (case command
              (:send :error)
              (enqueue
                (c/get-or-create message-channels (message-destination msg) nil)
                msg)

              nil))))

      (register-consumer [_ ch]
        (let [bridges (c/channel-cache (fn [_] (channel)))]
          (receive-all ch
            (fn [{:keys [command] :as msg}]
              (case command

                :subscribe
                (siphon
                  (c/subscribe outer-router (message-destination msg) {})
                  (c/get-or-create bridges (message-id msg) nil)
                  ch)

                :unsubscribe
                (close (c/get-or-create bridges (message-id msg) nil))

                nil)))))

      IRouter
      (inner-cache [_]
        (c/inner-cache inner-router))
      (subscribe [_ topic options]
        (c/subscribe outer-router topic options)))))

;;;
