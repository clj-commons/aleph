;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.channel
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defprotocol Channel
  (listen [ch f])
  (listen-all [ch f])
  (receive! [ch f]
    "Adds a callback which will receive the next message from the channel.")
  (receive-all! [ch f]
    "Adds a callback which will receive all messages from the channel.")
  (cancel-callback [ch f]
    "Removes a permanent or transient callback from the channel.")
  (enqueue [ch msg]
    "Enqueues a message into the channel.")
  (enqueue-and-close [ch sg]
    "Enqueues the final message into the channel.  When this message is received,
     the channel will be closed.")
  (closed? [ch]
    "Returns true if the channel has been closed."))

(defn channel? [ch]
  (satisfies? Channel ch))

;;;

(def delayed-executor (ScheduledThreadPoolExecutor. 1))

(defn delay-invoke [f delay]
  (.schedule delayed-executor f (long delay) TimeUnit/MILLISECONDS))

;;;

(defn result-channel
  "A channel which is meant to permanently hold a single value.  Only a single value
   can be enqueued, and this value can be received an unlimited number of times."
  []
  (let [result (ref nil)
	complete (ref false)
	listeners (ref #{})]
    (reify Channel
      (toString [_]
	(str
	  "result-channel: "
	  (if @complete
	    @result
	    :incomplete)))
      (listen [this f]
	(when-let [value (dosync
			   (if @complete
			     @result
			     (do
			       (alter listeners conj f)
			       nil)))]
	  (f value))
	nil)
      (listen-all [this f]
	(listen this f))
      (receive-all! [this f]
	(listen this f))
      (receive! [this f]
	(listen this f))
      (cancel-callback [_ f]
	(dosync
	  (alter listeners disj f)))
      (enqueue [_ msg]
	(doseq [f (dosync
		    (when @complete
		      (throw (Exception. "Channel already contains a result.")))
		    (ref-set result msg)
		    (ref-set complete true)
		    (let [coll @listeners]
		      (ref-set listeners nil)
		      coll))]
	  (f msg))
	nil)
      (enqueue-and-close [_ _]
	(throw (Exception. "Cannot close result-channel.")))
      (closed? [_]
	false))))

(defn channel
  "A basic implementation of a channel with an unbounded queue."
  []
  (let [messages (ref [])
	transient-receivers (ref #{})
	receivers (ref #{})
	transient-listeners (ref #{})
	listeners (ref #{})
	closed (ref false)
	callbacks (fn []
		    (dosync
		      (when-not (empty? @messages)
			(let [close (= ::close (second @messages))
			      first-recipients (concat
						 @transient-receivers
						 @transient-listeners)
			      all-recipients (concat
					       @receivers
					       @listeners)]
			  (when close
			    (ref-set closed true))
			  (try
			    (if-not (and (empty? @receivers) (empty? @transient-receivers))
			      (list*
				[(first @messages) (concat first-recipients all-recipients)]
				(partition 2 (interleave (rest @messages) all-recipients)))
			      (when (some identity (doall (map #(% (first @messages)) first-recipients)))
				(ref-set messages
				  (loop [msgs (next @messages)]
				    (if (and msgs (some identity (doall (map #(% (first msgs)) all-recipients))))
				      (recur (next msgs))
				      (vec msgs))))
				nil))
			    (finally
			      (ref-set transient-listeners #{})
			      (if-not (empty? @receivers)
				(ref-set messages [])
				(when-not (empty? @transient-receivers)
				  (alter messages (comp vec next))))
			      (ref-set transient-receivers #{})))))))
	send-to-callbacks (fn [callbacks]
			    (doseq [[msg fns] callbacks]
			      (doseq [f fns]
				(f msg))))]
    (reify Channel
      Object
      (toString [_]
	(str
	  "\nmessages: " @messages "\n"
	  "listeners: " @listeners "\n"
	  "transient-listeners: " @transient-listeners "\n"
	  "receivers: " @receivers "\n"
	  "transient-receivers: " @transient-receivers "\n"))
      (receive-all! [_ f]
	(send-to-callbacks
	  (dosync
	    (alter receivers conj f)
	    (callbacks))))
      (receive! [this f]
	(send-to-callbacks
	  (dosync
	    (alter transient-receivers conj f)
	    (callbacks)))
	this)
      (listen [this f]
	(send-to-callbacks
	  (dosync
	    (alter transient-listeners conj f)
	    (callbacks)))
	this)
      (listen-all [_ f]
	(send-to-callbacks
	  (dosync
	    (alter listeners conj f)
	    (callbacks)))
	@messages)
      (cancel-callback [_ f]
	(dosync
	  (alter listeners disj f)
	  (alter transient-listeners disj f)
	  (alter receivers disj f)
	  (alter transient-receivers disj f)))
      (enqueue [this msg]
	(send-to-callbacks
	  (dosync
	    (alter messages conj msg)
	    (callbacks)))
	this)
      (enqueue-and-close [_ msg]
	(send-to-callbacks
	  (dosync
	    (alter messages concat [msg ::close])
	    (callbacks))))
      (closed? [_]
	@closed))))

;;;

(defn poll
  [channel-map timeout]
  (let [received (ref false)
	result (result-channel)
	enqueue-fn (fn [k]
		     (fn this
		       ([]
			  (this nil))
		       ([x]
			  (when
			    (dosync
			      (when-not @received
				(ref-set received true)
				true))
			    (enqueue result [k x])
			    true))))]
    (doseq [[k ch] channel-map]
      (listen ch (enqueue-fn k)))
    (when (pos? timeout)
      (delay-invoke (enqueue-fn nil) timeout))
    result))
