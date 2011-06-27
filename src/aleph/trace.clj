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

(def active-probe-suffix "aleph::probes::active::")
(def active-probe-update-broadcast "aleph::updated::active-probes")

(defn all-vals [client]
  (let [keys @(client [:keys "*"])]
    (zipmap keys (map #(deref (client [:get %])) keys))))

(defn increment-probe [client probe]
  (let [probe (str active-probe-suffix probe)]
    (client [:incr probe])))

(defn decrement-probe [options probe]
  (let [probe (str active-probe-suffix probe)
	client (redis-client options)]
    (async
      (do
	(force
	  (loop []
	    (client [:watch probe])
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
			(client [:publish active-probe-update-broadcast "updated"])))))))))
	(close-connection client)))))

(defn watch-active-probes [client options]
  (let [ch (channel [])]
    (run-pipeline (redis-stream options)
      (fn [stream]
	(subscribe stream active-probe-update-broadcast)
	(run-pipeline (client [:keys (str active-probe-suffix "*")])
	  (fn [current-value]
	    ;; seed with current value
	    (enqueue ch current-value)
	    ;; fetch value when notified of update
	    (receive-all stream
	      (fn [_]
		(run-pipeline
		  (client [:keys (str active-probe-suffix "*")])
		  #(enqueue ch %))))))))
    (map*
      (fn [vals]
	(->> vals
	  (map #(.substring ^String % (count active-probe-suffix) (count %)))
	  set))
      ch)))

(defn forward-probes
  ""
  [options]
  (let [client (redis-client options)
	probes (partition* 2 1 (watch-active-probes client options))
	probe-links (atom {})]
    (receive-all (fork probes) println)
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
	      (siphon (probe-channel probe) ch)
	      ;; publish all values
	      (receive-all ch
		(fn [msg]
		  (when-not (and (nil? msg) (drained? ch))
		    (run-pipeline (client [:publish (str "aleph::probes::" probe) (pr-str msg)])
		      #(when-not (pos? %)
			 ;; if no one's listening, take out the probe
			 (decrement-probe options probe)))))))
	    (swap! probe-links merge new-links)))))))

(defn consume-probe [probe client stream options]
  (let [ch (channel)
	stream-name (str "aleph::probes::" probe)]
    (siphon
      (->> stream
	(filter* #(= stream-name (:stream %)))
	(map* #(read-string (:message %))))
      ch)
    (subscribe stream stream-name)
    (increment-probe client probe)
    (on-closed ch #(decrement-probe options probe))
    ch))

(defn setup-test []
  (let [options {:host "localhost"}
	client (redis-client options)
	stream (redis-stream options)]
    (forward-probes options)
    (consume-probe "test" client stream options)))
