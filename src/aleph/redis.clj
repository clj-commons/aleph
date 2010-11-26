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
    [lamina core]
    [aleph.redis protocol]))

(defn redis-client
  ([host]
     (redis-client :utf-8 host))
  ([charset host]
     (redis-client charset host 6379))
  ([charset host port]
     (run-pipeline (tcp-client {:host host :port port :frame (redis-codec charset)})
       (fn [ch]
	 (let [request-channel (channel)]
	   (siphon (map* process-request request-channel) ch)
	   (splice (map* process-response ch) request-channel))))))




