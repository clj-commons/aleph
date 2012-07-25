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
    [aleph.stomp.codec :as c]))

(defn stomp-connection [options]
  (tcp-client (assoc options :frame c/message-codec)))

(defn start-router [options]
  (let [name (or
               (:name options)
               (-> options :server :name)
               "stomp-router")
        r (r/router {:name name})]
    (start-tcp-server
      (fn [ch _]
        (r/register-publisher r nil ch)
        (r/register-subscriber r ch))
      (assoc options
        :name name
        :frame c/message-codec))))

(defn endpoint [producer options]
  (let [name (or
               (:name options)
               "stomp-endpoint")
        conn #(tcp-client
                (assoc options
                  :name name
                  :frame c/message-codec))]
    (r/endpoint name conn producer)))

(import-fn r/subscribe)
