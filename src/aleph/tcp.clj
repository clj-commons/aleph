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
    [aleph netty])
  (:import
    [java.nio.channels
     ClosedChannelException]))

(defn start-tcp-server [handler options]
  (let [server-name (or
                      (:name options)
                      (-> options :server :name)
                      "tcp-server")
        error-predicate (or
                          (:error-predicate options)
                          #(not (instance? ClosedChannelException %)))]
    (doseq [[k v] (:probes options)]
      (siphon (probe-channel [server-name k]) v))
    (start-server
      (fn [channel-group]
        (create-netty-pipeline server-name error-predicate channel-group
          :handler (server-message-handler handler)))
      options)))
