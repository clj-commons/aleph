;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.core.channel
  (:use
    [clojure.pprint]
    [clojure.contrib.seq :only (separate)])
  (:import
    [java.util.concurrent
     ConcurrentLinkedQueue
     ScheduledThreadPoolExecutor
     TimeUnit
     TimeoutException]
    [clojure.lang
     Counted]))

(defprotocol AlephChannel
  (listen- [ch fs])
  (receive-while- [ch callback-predicate-map])
  (receive- [ch fs])
  (receive-all- [ch fs])
  (cancel-callback- [ch fs])
  (enqueue- [ch msgs])
  (enqueue-and-close- [ch msgs])
  (sealed? [ch]
    "Returns true if no further messages can be enqueued.")
  (closed? [ch]
    "Returns true if queue is sealed and there are no pending messages."))

(defn channel? [ch]
  (satisfies? AlephChannel ch))

;;;

(defn listen 
  "Adds one or more callback which will receive all new messages.  If a callback returns a
   function, that function will consume the message.  Otherwise, it should return nil.  The
   callback is run within a transaction, and may receive the same message multiple times.

   This exists to support poll, don't use it directly unless you know what you're doing."
  [ch & callbacks]
  (listen- ch callbacks))

(defn receive-while
  [ch & {:as callback-predicate-map}]
  (receive-while- ch callback-predicate-map))

(defn receive
  "Adds one or more callbacks which will receive the next message from the channel."
  [ch & callbacks]
  (receive- ch callbacks))

(defn receive-all
  "Adds one or more callbacks which will receive all messages from the channel."
  [ch & callbacks]
  (receive-all- ch callbacks))

(defn cancel-callback
  "Cancels one or more callbacks."
  [ch & callbacks]
  (cancel-callback- ch callbacks))

(defn enqueue
  "Enqueues messages into the channel."
  [ch & messages]
  (enqueue- ch messages))

(defn enqueue-and-close
  "Enqueues the final messages into the channel.  When this message is received,
   the channel will be closed."
  [ch & messages]
  (enqueue-and-close- ch messages))

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
       (apply enqueue ch message)
       ch))
  ([]
     (let [result (ref nil)
	   complete (ref false)
	   listeners (ref #{})
	   receivers (ref #{})

	   subscribe
	   (fn [fs set handler]
	     (let [value (dosync
			   (if @complete
			     @result
			     (do
			       (apply alter set conj fs)
			       ::incomplete)))]
	       (when-not (= ::incomplete value)
		 (doseq [f fs]
		   (handler f value))))
	     nil)]

       ^{:type ::constant-channel}
       (reify AlephChannel Counted
	 (count [_]
	   (if @complete 1 0))
	 (toString [_]
	   (if @complete
	     (->> (with-out-str (pprint @result))
	       drop-last
	       (apply str))))
	 (listen- [this fs]
	   (subscribe fs listeners
	     #(when-let [f (%1 %2)]
		(f %2)))
	   true)
	 (receive-all- [this fs]
	   (receive this fs)
	   true)
	 (receive-while- [this callback-predicate-map]
	   (doseq [[f pred] callback-predicate-map]
	     (receive- this #(when (pred %) (f %))))
	   true)
	 (receive- [this fs]
	   (subscribe fs receivers #(%1 %2))
	   true)
	 (cancel-callback- [_ fs]
	   (dosync
	     (apply alter listeners disj fs)))
	 (enqueue- [_ msgs]
	   (when-not (= 1 (count msgs))
	     (throw (Exception. "Constant channels can only contain a single message.")))
	   (let [msg (first msgs)
		 callbacks (dosync
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
	 (enqueue-and-close- [this msgs]
	   (enqueue- this msgs))
	 (sealed? [_]
	   @complete)
	 (closed? [_]
	   false)))))

(defn channel
  "An implementation of a unidirectional channel with an unbounded queue."
  [& messages]
  (let [messages (ref (if (empty? messages)
			clojure.lang.PersistentQueue/EMPTY
			(apply conj clojure.lang.PersistentQueue/EMPTY messages)))
	transient-receivers (ref #{})
	receivers (ref #{})
	listeners (ref #{})
	conditional-receivers (ref {})
	   
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

	conditional-receiver-callbacks
	(fn []
	  (ensure conditional-receivers)
	  (let [receiver-map @conditional-receivers]
	    (when-not (empty? receiver-map)
	      (loop [messages @messages, receivers (keys receiver-map), callbacks []]
		(if (empty? messages)
		  (do
		    (alter conditional-receivers #(apply hash-map (mapcat list receivers (map % receivers))))
		    callbacks)
		 (let [msg (first messages)
		       receivers (filter #((receiver-map %) msg) receivers)]
		   (if (empty? receivers)
		     (do
		       (ref-set conditional-receivers {})
		       callbacks)
		     (recur (rest messages) receivers (conj callbacks [msg receivers])))))))))
	   
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
			     (transient-receiver-callbacks)
			     (conditional-receiver-callbacks)]]
	      (let [message-count (apply max (map count callbacks))]
		(if (= 1 message-count)
		  (alter messages pop)
		  (alter messages #(reduce (fn [s f] (f s)) % (repeat message-count pop)))))
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
	  (not (and @sealed (empty? @messages))))

	assert-fns
	(fn [fs]
	  (when-not (every? fn? fs) (throw (Exception. "All callbacks must be functions."))))]
    ^{:type ::channel}
    (reify AlephChannel Counted
      (count [_]
	(count @messages))
      (toString [_]
	(->> (with-out-str (pprint (vec @messages)))
	  drop-last
	  (apply str)))
      (receive-all- [_ fs]
	(assert-fns fs)
	(send-to-callbacks
	  (dosync
	    (if-not (can-receive?)
	      ::invalid
	      (do
		(apply alter receivers conj fs)
		(callbacks))))))
      (receive- [this fs]
	(assert-fns fs)
	(send-to-callbacks
	  (dosync
	    (if-not (can-receive?)
	      ::invalid
	      (do
		(apply alter transient-receivers conj fs)
		(callbacks))))))
      (receive-while- [this callback-predicate-map]
	(assert-fns (keys callback-predicate-map))
	(assert-fns (vals callback-predicate-map))
	(send-to-callbacks
	  (dosync
	    (if-not (can-receive?)
	      ::invalid
	      (do
		(apply alter conditional-receivers merge callback-predicate-map)
		(callbacks))))))
      (listen- [this fs]
	(assert-fns fs)
	(send-to-callbacks
	  (dosync
	    (if-not (can-receive?)
	      ::invalid
	      (do
		(when-not (every? fn? fs) (throw (Exception. "all callbacks must be functions")))
		(apply alter listeners conj fs)
		(callbacks))))))
      (cancel-callback- [_ fs]
	(assert-fns fs)
	(dosync
	  (apply alter listeners disj fs)
	  (apply alter receivers disj fs)
	  (apply alter transient-receivers disj fs)
	  (apply alter conditional-receivers dissoc fs)))
      (enqueue- [this msgs]
	(send-to-callbacks
	  (dosync
	    (if-not (can-enqueue?)
	      ::invalid
	      (do
		(apply alter messages conj msgs)
		(callbacks))))))
      (enqueue-and-close- [_ msgs]
	(send-to-callbacks
	  (dosync
	    (if-not (can-enqueue?)
	      ::invalid
	      (do
		(ref-set sealed true)
		(apply alter messages conj msgs)
		(callbacks))))))
      (sealed? [_]
	@sealed)
      (closed? [_]
	(and @sealed (empty? @messages))))))

;;;

(def nil-channel
  ^{:type ::channel}
  (reify AlephChannel Counted
    (count [_] 0)
    (toString [_] "[]")
    (receive- [_ fs] false)
    (receive-all- [_ fs] false)
    (receive-while- [_ callback-predicate-map] false)
    (listen- [_ f] false)
    (cancel-callback- [_ fs])
    (closed? [_] true)
    (sealed? [_] true)
    (enqueue- [_ msgs] false)
    (enqueue-and-close- [_ msgs] false)))

(defn sealed-channel
  "Returns a channel containing 'messages' which is already sealed."
  [& messages]
  (if (empty? messages)
    nil-channel
    (let [ch (channel)]
      (enqueue-and-close- ch messages)
      ch)))

(defn splice
  "Splices together a message source and a message destination
   into a single channel."
  [src dst]
  ^{:type ::channel}
  (reify AlephChannel Counted
    (count [_]
      (count src))
    (toString [_]
      (str src))
    (receive- [_ fs]
      (receive- src fs))
    (receive-all- [_ fs]
      (receive-all- src fs))
    (receive-while- [_ callback-predicate-map]
      (receive-while src callback-predicate-map))
    (listen- [_ fs]
      (listen- src fs))
    (cancel-callback- [_ fs]
      (cancel-callback- src fs))
    (closed? [_]
      (closed? src))
    (sealed? [_]
      (sealed? dst))
    (enqueue- [_ msgs]
      (enqueue- dst msgs))
    (enqueue-and-close- [_ msgs]
      (enqueue-and-close- dst msgs))))

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

(defn siphon-while
  "Enqueues all messages from src to dst until (pred msg) fails or dst is sealed."
  [pred src dst]
  (receive-while src pred
    (fn this [msg]
      (let [cancel (not (enqueue dst msg))]
	(when cancel
	  (cancel-callback src this))))))

(defn siphon
  "Automatically enqueues all messages from 'src' into 'dst',
   unless 'dst' has been sealed."
  [src dst]
  (receive-all src
    (fn this [msg]
      (let [cancel (not (enqueue dst msg))]
	(when cancel
	  (cancel-callback src this))))))

(defn wrap-channel
  "Returns a new channel which maps 'f' over all messages from 'ch'."
  [ch f]
  (let [ch* (channel)]
    (receive-all ch #(enqueue ch* (f %)))
    ch*))

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
