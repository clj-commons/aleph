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

(defn enqueue-task [redis-client queue-name task]
  (redis-client ["lpush" queue-name (pr-str task)]))

(defn receive-task [redis-client & queue-names]
  (run-pipeline
    (redis-client (concat ["brpop"] queue-names [0]))
    #(hash-map :queue (first %) :message (read-string (second %)))))

(defn redis-stream
  "Returns a channel representing a subscription stream using Redis' pub-sub functionality."
  ([host]
     (redis-stream :utf-8 host))
  ([charset host]
     (redis-stream charset host 6379))
  ([charset host port]
     (let [control-messages (channel)
	   stream (channel)
	   control-message-accumulator (atom [])]
       (receive-all control-messages
	 #(swap! control-message-accumulator conj %))
       (let [connection (persistent-connection
			  #(tcp-client {:host host :port port :frame (redis-codec charset)})
			  (str "redis stream @ " host ":" port)
			  (fn [ch]
			    ;; NOTE: this is a bit of a race condition (subscription messages may be sent twice), but
			    ;; subscription messages are idempotent.  Regardless, maybe clean this up.
			    (let [control-messages* (fork control-messages)]
			      (doseq [msg @control-message-accumulator]
				(enqueue ch msg))
			      (siphon control-messages* ch))
			    (siphon 
			      (->> ch
				(filter* #(and (sequential? %) (= "message" (-> % first str))))
				(map* #(hash-map :channel (nth % 1) :message (nth % 2))))
			      stream)))]
	 (with-meta (splice stream control-messages) {::connection connection})))))

(defn subscribe [redis-stream & channel-names]
  (doseq [c channel-names]
    (enqueue redis-stream ["subscribe" c])))

(defn pattern-subscribe [redis-stream & channel-patterns]
  (doseq [c channel-patterns]
    (enqueue redis-stream ["psubscribe" c])))

(defn unsubscribe [redis-stream & channel-names]
  (doseq [c channel-names]
    (enqueue redis-stream ["unsubscribe" c])))

(defn pattern-unsubscribe [redis-stream & channel-patterns]
  (doseq [c channel-patterns]
    (enqueue redis-stream ["punsubscribe" c])))

(defn close-stream [redis-stream]
  (close-connection (-> redis-stream meta ::connection)))






