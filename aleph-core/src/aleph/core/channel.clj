;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.core.channel
  (:use [clojure.pprint])
  (:import
    [java.util.concurrent
     ConcurrentLinkedQueue
     ScheduledThreadPoolExecutor
     TimeUnit
     TimeoutException]))

(defprotocol AlephChannel
  (listen [ch f]
    "Adds a callback which will receive all new messages.  If the callback returns a function,
     that function will consume the message.  Otherwise, it should return nil.  The callback
     may receive the same message multiple times.

     This exists to support poll, don't use it directly unless you know what you're doing.")
  (receive [ch f]
    "Adds a callback which will receive the next message from the channel.")
  (receive-all [ch f]
    "Adds a callback which will receive all messages from the channel.")
  (cancel-callback [ch f]
    "Removes a permanent or transient callback from the channel.")
  (enqueue [ch msg]
    "Enqueues a message into the channel.")
  (enqueue-and-close [ch sg]
    "Enqueues the final message into the channel.  When this message is received,
     the channel will be closed.")
  (sealed? [ch]
    "Returns true if no further messages can be enqueued.")
  (closed? [ch]
    "Returns true if queue is sealed and there are no pending messages."))

(defn channel? [ch]
  (satisfies? AlephChannel ch))

;;;

(def delayed-executor (ScheduledThreadPoolExecutor. 1))

(defn delay-invoke [f delay]
  (.schedule ^ScheduledThreadPoolExecutor delayed-executor ^Runnable f (long delay) TimeUnit/MILLISECONDS))

;;;

(defn constant-channel
  "A channel which can hold zero or one messages in the queue.  Once it has
   a message, that message cannot be consumed.  Meant to communicate a single,
   constant value via a channel."
  ([message]
     (let [ch (constant-channel)]
       (enqueue ch message)
       ch))
  ([]
     (let [result (ref nil)
	   complete (ref false)
	   listeners (ref #{})
	   receivers (ref #{})

	   subscribe
	   (fn [f set handler]
	     (let [value (dosync
			   (if @complete
			     @result
			     (do
			       (alter set conj f)
			       ::incomplete)))]
	       (when-not (= ::incomplete value)
		 (handler f value)))
	     nil)]

       ^{:type ::constant-channel}
       (reify AlephChannel
	 (toString [_]
	   (if @complete
	     (->> (with-out-str (pprint @result))
	       drop-last
	       (apply str))))
	 (listen [this f]
	   (subscribe f listeners
	     #(when-let [f (%1 %2)]
		(f %2)))
	   true)
	 (receive-all [this f]
	   (receive this f)
	   true)
	 (receive [this f]
	   (subscribe f receivers #(%1 %2))
	   true)
	 (cancel-callback [_ f]
	   (dosync
	     (alter listeners disj f)))
	 (enqueue [_ msg]
	   (let [callbacks (dosync
			     (if @complete
			       ::invalid
			       (do
				 (ref-set result msg)
				 (ref-set complete true)
				 (let [coll (filter identity
					      (doall
						(concat
						  (map #(% msg) @listeners)
						  @receivers)))]
				   (ref-set listeners nil)
				   (ref-set receivers nil)
				   coll))))]
	     (if (= ::invalid callbacks)
	       false
	       (do
		 (doseq [f callbacks]
		   (f msg))
		 true))))
	 (enqueue-and-close [this msg]
	   (enqueue this msg))
	 (sealed? [_]
	   @complete)
	 (closed? [_]
	   false)))))

(defn channel
  "An implementation of a unidirectional channel with an unbounded queue."
  ([& messages]
     (let [ch (channel)]
       (doseq [msg messages]
	 (enqueue ch msg))
       ch))
  ([]
     (let [messages (ref [])
	   transient-receivers (ref #{})
	   receivers (ref #{})
	   listeners (ref #{})
	   sealed (ref false)

	   listener-callbacks
	   (fn []
	     (ensure listeners)
	     (let [msg (first @messages)
		   callbacks (filter identity (map #(% msg) @listeners))]
	       (ref-set listeners #{})
	       (when-not (empty? callbacks)
		 [[msg callbacks]])))

	   receiver-callbacks
	   (fn []
	     (ensure receivers)
	     (let [callbacks @receivers]
	       (when-not (empty? callbacks)
		 (map #(list % callbacks) @messages))))

	   transient-receiver-callbacks
	   (fn []
	     (ensure transient-receivers)
	     (let [callbacks @transient-receivers]
	       (when-not (empty? callbacks)
		 (ref-set transient-receivers #{})
		 [[(first @messages) callbacks]])))

	   callbacks
	   (fn []
	     (ensure messages)
	     (when-not (empty? @messages)
	       (let [callbacks [(listener-callbacks)
				(receiver-callbacks)
				(transient-receiver-callbacks)]]
		 (alter messages #(vec (drop (apply max (map count callbacks)) %)))
		 (when (and (empty? @messages) @sealed)
		   (ref-set receivers nil))
		 (apply concat callbacks))))

	   send-to-callbacks
	   (fn [callbacks]
	     (if (= ::invalid callbacks)
	       false
	       (do
		 (doseq [[msg fns] callbacks]
		   (doseq [f fns]
		     (f msg)))
		 true)))

	   can-enqueue?
	   (fn []
	     (not @sealed))

	   can-receive?
	   (fn []
	     (not (and @sealed (empty? @messages))))]
       ^{:type ::channel}
       (reify AlephChannel
	 Object
	 (toString [_]
	   (->> (with-out-str (pprint @messages))
	     drop-last
	     (apply str)))
	 (receive-all [_ f]
	   (send-to-callbacks
	     (dosync
	       (if-not (can-receive?)
		 ::invalid
		 (do
		   (alter receivers conj f)
		   (callbacks))))))
	 (receive [this f]
	   (send-to-callbacks
	     (dosync
	       (if-not (can-receive?)
		 ::invalid
		 (do
		   (alter transient-receivers conj f)
		   (callbacks))))))
	 (listen [this f]
	   (send-to-callbacks
	     (dosync
	       (if-not (can-receive?)
		 ::invalid
		 (do
		   (alter listeners conj f)
		   (callbacks))))))
	 (cancel-callback [_ f]
	   (dosync
	     (alter listeners disj f)
	     (alter receivers disj f)
	     (alter transient-receivers disj f)))
	 (enqueue [this msg]
	   (send-to-callbacks
	     (dosync
	       (if-not (can-enqueue?)
		 ::invalid
		 (do
		   (alter messages conj msg)
		   (callbacks))))))
	 (enqueue-and-close [_ msg]
	   (send-to-callbacks
	     (dosync
	       (if-not (can-enqueue?)
		 ::invalid
		 (do
		   (ref-set sealed true)
		   (alter messages conj msg)
		   (callbacks))))))
	 (sealed? [_]
	   @sealed)
	 (closed? [_]
	   (and @sealed (empty? @messages)))))))


;;;

(def closed-channel
  ^{:type ::channel}
  (reify AlephChannel
    (toString [_]
      "<== []")
    (receive [_ f]
      false)
    (receive-all [_ f]
      false)
    (listen [_ f]
      false)
    (cancel-callback [_ f]
      )
    (closed? [_]
      true)
    (sealed? [_]
      true)
    (enqueue [_ msg]
      false)
    (enqueue-and-close [_ msg]
      false)))

(def nil-channel
  ^{:type ::channel}
  (reify AlephChannel
    (toString [_]
      "<== []")
    (receive [_ f])
    (receive-all [_ f])
    (listen [_ f])
    (cancel-callback [_ f])
    (closed? [_]
      false)
    (sealed? [_]
      false)
    (enqueue [_ msg])
    (enqueue-and-close [_ msg])))

(defn splice
  "Splices together a message source and a message destination
   into a single channel."
  [src dst]
  ^{:type ::channel}
  (reify AlephChannel
    (toString [_]
      (str src))
    (receive [_ f]
      (receive src f))
    (receive-all [_ f]
      (receive-all src f))
    (listen [_ f]
      (listen src f))
    (cancel-callback [_ f]
      (cancel-callback src f))
    (closed? [_]
      (closed? src))
    (sealed? [_]
      (sealed? dst))
    (enqueue [_ msg]
      (enqueue dst msg))
    (enqueue-and-close [_ msg]
      (enqueue-and-close dst msg))))

(defn channel-pair
  "Creates paired channels, where an enqueued message from one channel
   can be received from the other."
  ([]
     (channel-pair (channel) (channel)))
  ([a b]
     [(splice a b) (splice b a)]))

;;;

(defn poll
  "Allows you to consume exactly one message from multiple channels.

   If the function is called with (poll {:a a, :b b}), and channel 'a' is
   the first to emit a message, the function will return a constant channel
   which emits [:a message].

   If the poll times out, the constant channel will emit 'nil'.  If a timeout
   is not specified, the poll will never time out."
  ([channel-map]
     (poll channel-map -1))
  ([channel-map timeout]
     (let [received (ref false)
	   result-channel (constant-channel)
	   enqueue-fn (fn [k]
			(fn [v]
			  (dosync
			    (when-not @received
			      (ref-set received true)
			      #(enqueue result-channel (when k [k %]))))))]
       (doseq [[k ch] channel-map]
	 (listen ch (enqueue-fn k)))
       (let [listen-results (map
			      (fn [[k ch]] (listen ch (enqueue-fn k)))
			      channel-map)]
	 (when (or (zero? timeout) (every? #(not %) listen-results))
	   (let [enqueue-fn* ((enqueue-fn nil) nil)]
	     (when enqueue-fn*
	       (enqueue-fn* nil)))))
       (when (< 0 timeout)
	 (delay-invoke #(((enqueue-fn nil) nil) nil) timeout))
       result-channel)))

(defn lazy-channel-seq
  "Creates a lazy-seq which consumes messages from the channel.  Only elements
   which are realized will be consumes.

   (take 1 (lazy-channel-seq ch)) will only take a single message from the channel,
   and no more.  If there are no messages in the channel, execution will halt until
   a message is enqueued.

   'timeout' controls how long (in ms) the sequence will wait for each element.  If
   the timeout is exceeded or the channel is closed, the sequence will end.  By default,
   the sequence will never time out."
  ([ch]
     (lazy-channel-seq ch -1))
  ([ch timeout]
     (let [timeout-fn (if (fn? timeout)
			timeout
			(constantly timeout))]
       (lazy-seq
	 (when-not (closed? ch)
	   (let [value (promise)]
	     (receive (poll {:ch ch} (timeout-fn))
	       #(deliver value
		  (when (first %)
		    [(second %)])))
	     (when @value
	       (concat @value (lazy-channel-seq ch timeout-fn)))))))))

(defn channel-seq
  "Creates a non-lazy sequence which consumes all messages from the channel within the next
   'timeout' milliseconds.  A timeout of 0, which is the default, will only consume messages
   currently within the channel.

   This call is synchronous, and will hang the thread until the timeout is reached or the channel
   is closed."
  ([ch]
     (channel-seq ch 0))
  ([ch timeout]
     (doall
       (lazy-channel-seq ch
	 (let [t0 (System/currentTimeMillis)]
	   #(max 0 (- timeout (- (System/currentTimeMillis) t0))))))))

(defn wait-for-message
  "Synchronously onsumes a single message from a channel.  If no message is received within the timeout,
   a java.util.concurrent.TimeoutException is thrown.  By default, this function will not time out."
  ([ch]
     (wait-for-message ch -1))
  ([ch timeout]
     (let [msg (take 1 (lazy-channel-seq ch timeout))]
       (if (empty? msg)
	 (throw (TimeoutException. "Timed out waiting for message from channel."))
	 (first msg)))))

(defn siphon-when
  "Enqueues all messages that satisify 'pred' from 'src' into 'dst',
   unless 'dst' has been sealed."
  [pred src dst]
  (receive-all src
    (fn this [msg]
      (let [cancel (if (pred msg)
		     (not (enqueue dst msg))
		     (sealed? dst))]
	(when cancel
	  (cancel-callback src this))))))

(defn siphon
  "Automatically enqueues all messages from 'src' into 'dst',
   unless 'dst' has been sealed."
  [src dst]
  (siphon-when (constantly true) src dst))

(defn wrap-channel
  "Returns a new channel which maps 'f' over all messages from 'ch'."
  [ch f]
  (let [ch* (channel)]
    (receive-all ch #(enqueue ch* (f %)))
    ch*))

(defn wrap-endpoint [ch receive-fn enqueue-fn]
  "Returns an endpoint which maps 'receive-fn' over all messages received from
   the endpoint, and maps 'enqueue-fn' over all messages enqueued into the
   endpoint."
  (let [in (channel)
	out (channel)]
    (receive-all ch #(enqueue out (receive-fn %)))
    (receive-all in #(enqueue ch (enqueue-fn %)))
    (splice out in)))

;;;

(def named-channels (ref {}))

(defn named-channel
  "Returns a unique channel for the key.  If no such channel exists,
   a channel is created, and 'creation-callback' is invoked."
  ([key]
     (named-channel key nil))
  ([key creation-callback]
     (let [[created? ch] (dosync
			   (if-let [ch (@named-channels key)]
			     [false ch]
			     (let [ch (channel)]
			       (commute named-channels assoc key ch)
			       [true ch])))]
       (when (and created? creation-callback)
	 (creation-callback ch))
       ch)))

(defn release-named-channel
  "Forgets the channel associated with the key, if one exists."
  [key]
  (dosync
    (commute named-channels dissoc key)))

;;;

(defmethod print-method ::channel [ch writer]
  (.write writer (str "<== " (.toString ch))))

(defmethod print-method ::constant-channel [ch writer]
  (let [s (.toString ch)]
    (.write writer (str "<== [" s (when-not (empty? s) " ...") "]"))))
