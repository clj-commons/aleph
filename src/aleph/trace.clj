;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace
  (:use
    [clojure set]
    [aleph redis]
    [lamina core trace connections])
  (:require
    [clojure.string :as str]))

;;;

(def probe-suffix "aleph::probes::")
(def probe-counts-suffix "aleph::probe-counts::")
(def probe-counts-updated-broadcast "aleph::publish::probe-counts-updated")

;;;

(defn all-vals [client]
  (let [keys @(client [:keys "*"])]
    (zipmap keys (map #(deref (client [:get %])) keys))))

(defn reset-probes [options]
  (let [client (redis-client options)]
    (doseq [k @(client [:keys (str probe-counts-suffix "*")])]
      @(client [:del k]))
    (close-connection client)))

(defn increment-probe [client probe]
  (let [probe (str probe-counts-suffix probe)]
    (run-pipeline (client [:incr probe])
      (fn [cnt]
	(if (= 1 cnt)
	  (client [:publish probe-counts-updated-broadcast "created"]))))))

(defn decrement-probe [options probe]
  (let [probe (str probe-counts-suffix probe)
	client (redis-client options)]
    (async
      (do
	(force
	  (loop []
	    ;;(client [:watch probe])
	    (let [val (client [:get probe])]
	      (when val
		(let [val (read-string val)]
		  (client [:multi])
		  (if (<= val 1)
		    (client [:del probe])
		    (client [:decr probe]))
		  (let [result (client [:exec])]
		    (if (empty? result)
		      (recur)
		      (when (<= val 1)
			(client [:publish probe-counts-updated-broadcast "deleted"])))))))))
	(close-connection client)))))

;;;

(defn watch-active-probes [client options]
  (let [ch (channel [])
	stream (redis-stream options)]
    (run-pipeline stream 
      (fn [stream]
	(subscribe stream probe-counts-updated-broadcast)
	(run-pipeline (client [:keys (str probe-counts-suffix "*")])
	  (fn [current-value]
	    ;; seed with current value
	    (enqueue ch current-value)
	    ;; fetch value when notified of update
	    (receive-all stream
	      (fn [msg]
		(when msg
		  (run-pipeline
		    (client [:keys (str probe-counts-suffix "*")])
		    #(enqueue ch %)))))))))
    (on-closed ch #(close stream))
    (map*
      (fn [vals]
	(->> vals
	  (map #(.substring ^String % (count probe-counts-suffix) (count %)))
	  set))
      ch)))

(defn generate-probe [probe]
  (let [[probe filter] (str/split probe #"\.")
	filter (when filter (read-string filter))
	ch (probe-channel probe)]
    (if-not filter
      ch
      (filter*
	(fn [msg]
	  (every?
	    (fn [[k v]]
	      (and
		(map? msg)
		(= v (get msg k ::none))))
	    filter))
	ch))))

(defn forward-probes
  ""
  [options]
  (let [client (redis-client options)
	probes (partition* 2 1 (watch-active-probes client options))
	probe-links (atom {})]
    (on-closed probes #(close-connection client))
    ;;(receive-all (fork probes) println)
    (receive-in-order probes
      (fn [[prev curr]]
	(let [added (difference curr prev)
	      removed (difference prev curr)]
	  ;; turn off probes we don't care about anymore
	  (doseq [link (-> @probe-links (select-keys removed) vals)]
	    (close link))
	  (apply swap! probe-links dissoc removed)
	  ;; hook up probes we do care about
	  (let [new-links (zipmap added (repeatedly channel))]
	    (doseq [[probe ch] new-links]
	      ;; forward all probe values 
	      (siphon (generate-probe probe) ch)
	      ;; publish all values
	      (receive-all ch
		(fn [msg]
		  (when-not (and (nil? msg) (drained? ch))
		    (run-pipeline (client [:publish (str probe-suffix probe) (pr-str msg)])
		      #(when-not (pos? %)
			 ;; if no one's listening, take out the probe
			 (decrement-probe options probe)))))))
	    (swap! probe-links merge new-links)))))
    (fn []
      (close probes))))

(defn consume-probe
  ([probe options]
     (let [client (redis-client options)
	   stream (redis-stream options)]
       (on-closed stream #(close-connection client))
       (let [ch (consume-probe probe client stream options)]
	 (on-closed ch #(close stream))
	 ch)))
  ([probe client stream options]
     (let [probe (name probe)
	   ch (channel)
	   stream-name (str probe-suffix probe)]
       (siphon
	 (->> stream
	   (filter* #(= stream-name (:stream %)))
	   (map* #(read-string (:message %))))
	 ch)
       (subscribe stream stream-name)
       (increment-probe client probe)
       (on-closed ch #(decrement-probe options probe))
       ch)))


