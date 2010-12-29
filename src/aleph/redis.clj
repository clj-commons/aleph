;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.redis
  (:use
    [aleph tcp]
    [lamina core connections]
    [aleph.redis protocol]))

(defn redis-client
  "Returns a function which represents a persistent connection to the Redis server
   located at 'host'.  The function expects a vector of strings and an optional timeout,
   in the form

   (f [\"set\" \"foo\" \"bar\"] timeout?)

   The function will return a result-channel representing the response.  To close the
   connection, use lamina.connections/close-connection."
  ([host]
     (redis-client :utf-8 host))
  ([charset host]
     (redis-client charset host 6379))
  ([charset host port]
     (client
       #(tcp-client {:host host :port port :frame (redis-codec charset)})
       (str "redis @ " host ":" port))))




