;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
  aleph.redis
  (:use
    [aleph tcp]
    [lamina core connections]
    [aleph.redis protocol]))

(defn redis-client
  "Returns a function which represents a persistent connection to the Redis server
   located at :host.  The 'options' may also specify the :port and :charset used for this
   client.  The function expects a vector of strings or keywords and an optional timeout,
   in the form

   (f [:set \"foo\" \"bar\"] timeout?)

   The function will return a result-channel representing the response.  To close the
   connection, use lamina.connections/close-connection."
  ([{:keys [host
            port
            charset
            name
            password
            heartbeat?]
     :or {heartbeat? true
          charset :utf-8}
     :as options}]
     (let [options (merge
                     {:host "localhost"
                      :port 6379
                      :name "redis-client"}
                     options)

           options (if heartbeat?
                     (update-in options [:heartbeat]
                       #(or %
                          {:request [:ping]
                           :timeout 10000
                           :interval 10000
                           :response-validator (fn [rsp] (= "PONG" rsp))}))
                     options)

           database (atom nil)

           on-connected (fn [ch]
                          (run-pipeline nil
                            {:error-handler (fn [_])}

                            ;; set password
                            (fn [_]
                              (when password
                                (enqueue ch [:auth password])
                                (read-channel ch)))

                            ;; set database
                            (fn [_]
                              (when-let [db @database]
                                (enqueue ch [:select db])
                                (read-channel ch)))

                            ;; invoke on-connected callback
                            (fn [_]
                              (when-let [callback (:on-connected options)]
                                (callback ch)))))

	   client-fn (pipelined-client
		       #(tcp-client (merge options {:frame (redis-codec charset)}))
		       (merge
			 options
			 {:on-connected on-connected}))]
       (with-meta
         (fn [& args]
           (run-pipeline (apply client-fn args)
             {:error-handler (fn [_])}
             (fn [result]

               ;; if it was a :select command, register the database
               (when (and (coll? (first args)) (= :select (ffirst args)))
                 (reset! database (-> args first second)))

               ;; coerce exceptions into an error-result
               (if (instance? Exception result)
                 (error-result result)
                 result))))
         {::heartbeat? heartbeat?}))))

;;;

(defn enqueue-task
  "Enqueues a task onto a Redis queue. 'task' must be a printable Clojure data structure."
  [redis-client queue-name task]
  (redis-client ["lpush" queue-name (pr-str task)]))

(defn receive-task
  "Receives a task from one of the specified queues.  Returns a result-channel which will
   emit a value with the structure {:queue \"queue-name\", :task 1}.  It is assumed that the
   value received from Redis will be a readable data structure, and :task will contain a
   Clojure data structure."
  [redis-client & queue-names]
  (assert (not (empty? queue-names)))
  (run-pipeline nil
    {:error-handler (fn [_])}
    (fn [_] (redis-client (concat ["brpop"] queue-names [5])))
    #(if (empty? %)
       (restart)
       (hash-map :queue (first %) :task (read-string (second %))))))

(defn task-channel
  "Returns a channel that will continuously consume tasks from the specified queue(s).

   Closing the channel will halt further consumption."
  [options & queue-names]
  (let [r (redis-client (assoc options :heartbeat? false))
        ch (channel)]
    (run-pipeline nil
      {:error-handler (fn [ex]
                        (close ch))}
      (fn [_]
        (if-not (closed? ch)
          (apply receive-task r queue-names)
          (complete nil)))
      #(enqueue ch %)
      (fn [_] (restart)))

    (on-closed ch #(close-connection r))

    ch))

;;;

(defn- filter-messages [ch]
  (->> ch
    (filter*
      #(and
	 (sequential? %)
	 (let [type (str (first %))]
	   (or (= "message" type) (= "pmessage" type)))))
    (map*
      #(let [cnt (count %)]
	 (hash-map
	   :channel (nth % (- cnt 2))
	   :message (nth % (- cnt 1)))))))

(defn redis-stream
  "Returns a channel representing a stream from the Redis server located at :host. 'options'
   may also specify the :port and :charset used for this stream.

   Initially, the stream is not subscribed to any channels; to receive events, subscribe to
   channels using (subscribe stream & channel-names) or
   (pattern-subscribe stream & channel-patterns). To unsubscribe, use (unsubscribe ...) and
   (pattern-unsubscribe ...).

   Messages from the stream will be of the structure:

      {:channel \"channel-name\", :message \"message\"}

   :message will always be a string."
  ([options]
     (let [control-messages (channel)
	   stream (channel)

           options (merge
                     {:port 6379
                      :host "localhost"
                      :charset :utf-8
                      :name "redis-stream"}
                     options
                     {:on-connected
                      (fn [ch]
                        (siphon (fork control-messages) ch)
                        (siphon (filter-messages ch) stream))})

           connection (persistent-connection
                        #(tcp-client (merge options {:frame (redis-codec (:charset options))}))
                        options)

           _ (connection) ;; force a connection, since persistent-connection is lazy
           
           close-fn (fn []
                      (close-connection connection)
                      (close stream)
                      (close control-messages))] 

       (on-closed control-messages close-fn)
       (on-closed stream close-fn)
       
       (with-meta
         (splice stream control-messages)
         {:lamina.connections/close-fn close-fn
          :lamina.connections/reset-fn #(reset-connection connection)}))))

(defn subscribe
  "Subscribes a stream to one or more streams.  Corresponds to the SUBSCRIBE command."
  [redis-stream & stream-names]
  (enqueue redis-stream (list* "subscribe" stream-names)))

(defn pattern-subscribe
  "Subscribes a stream to zero or more streams matching the patterns given.  Corresponds to
   the PSUBSCRIBE command."
  [redis-stream & stream-patterns]
  (enqueue redis-stream (list* "psubscribe" stream-patterns)))

(defn unsubscribe
  "Unsubscribes a stream from one or more streams.  Corresponds to the UNSUBSCRIBE command."
  [redis-stream & stream-names]
  (enqueue redis-stream (list* "unsubscribe" stream-names)))

(defn pattern-unsubscribe
  "Unsubscribes a stream from zero or more channels matching the patterns given.  Corresponds
   to the PUNSUBSCRIBE command."
  [redis-stream & stream-patterns]
  (enqueue redis-stream (list* "punsubscribe" stream-patterns)))
