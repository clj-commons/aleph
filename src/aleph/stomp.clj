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
    [aleph formats tcp]
    [lamina core connections trace])
  (:require
    [aleph.stomp.router :as r]
    [aleph.stomp.endpoint :as e]
    [aleph.stomp.codec :as c]))

(defn stomp-connection [options]
  (tcp-client (assoc options :frame c/message-codec)))

(defn start-stomp-router [options]
  (let [name (or
               (:name options)
               (-> options :server :name)
               "stomp-router")
        r (r/router (merge
                      options
                      {:name name}))]
    (start-tcp-server
      (fn [ch _]
        (r/register-publisher r nil ch)
        (r/register-subscriber r ch))
      (assoc options
        :name name
        :frame c/message-codec))))

(defn stomp-endpoint
  [{:keys [client-options
           name
           producer]
    :as options}]
  (let [name (or
               (:name options)
               "stomp-endpoint")
        conn #(tcp-client
                (assoc client-options
                  :name name
                  :frame c/message-codec))]
    (e/endpoint
      (assoc options
        :name name
        :connection-generator conn))))

(import-fn e/subscribe)
