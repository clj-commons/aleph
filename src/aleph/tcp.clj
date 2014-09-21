;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.tcp
  (:use
    [lamina core trace]
    [aleph netty formats])
  (:import
    [java.nio.channels
     ClosedChannelException]))

(defn- wrap-tcp-channel [options ch]
  (with-meta
    (wrap-socket-channel
      options
      (let [ch* (channel)]
        (join (map* bytes->channel-buffer ch*) ch)
        (splice ch ch*)))
    (meta ch)))

(defn start-tcp-server [handler options]
  (let [server-name (or
                      (:name options)
                      (-> options :server :name)
                      "tcp-server")]
    (start-server
      server-name
      (fn [channel-group]
        (create-netty-pipeline server-name true channel-group
          :handler (server-message-handler
                     (fn [ch x]
                       (handler (wrap-tcp-channel options ch) x)))))
      options)))

(defn tcp-client [options]
  (let [client-name (or
                      (:name options)
                      (-> options :client :name)
                      "tcp-client")]
    (run-pipeline nil
      {:error-handler (fn [_])}
      (fn [_]
        (create-client
          client-name
          (fn [channel-group]
            (create-netty-pipeline client-name false channel-group))
          options))
      (partial wrap-tcp-channel options))))
