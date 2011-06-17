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
    [lamina core trace]))

(defn- watch-value [client command key initial-value options]
  (let [ch (channel initial-value)
	fetch #(client [command (str "aleph::values::" (name key))])]
    (run-pipeline (redis-stream options)
      (fn [stream]
	(subscribe stream (str "aleph::updated::" key))
	(run-pipeline (fetch)
	  (fn [current-value]
	    ;; seed with current value
	    (enqueue ch current-value)
	    ;; set up fetching of value every time there's an update
	    (receive-all stream (fn [x] (run-pipeline (fetch) #(enqueue ch %))))))))
    ch))

(defn- update-value [client command key val]
  (run-pipeline (client [command (str "aleph::values::" key) val])
    #(when-not (= 0 %)
       (client [:publish (str "aleph::updated::" (name key)) "updated"]))))

(defn forward-probes
  ""
  [options]
  (let [client (redis-client options)
	probes (->> (watch-value client :smembers "probes" #{} options)
		 (map* set)
		 (partition* 2 1))
	probe-links (atom {})]
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
	      (siphon (probe-channel (canonical-probe probe)) ch)
	      ;; publish all values
	      (receive-all ch
		(fn [msg]
		  (when-not (and (nil? msg) (drained? ch))
		    (run-pipeline (client [:publish (str "aleph::probes::" probe) (pr-str msg)])
		      #(when-not (pos? %)
			 ;; if no one's listening, take out the probe
			 (update-value client :srem "probes" probe)))))))
	    (swap! probe-links merge new-links)))))))

(defn activate-probe [probe client stream]
  (let [ch (channel)
	stream-name (str "aleph::probes::" probe)]
    (siphon
      (->> stream
	(filter* #(= stream-name (:stream %)))
	(map* #(read-string (:message %))))
      ch)
    (subscribe stream stream-name)
    (update-value client :sadd "probes" probe)
    ch))

(defn setup-test []
  (let [client (redis-client {:host "localhost"})
	stream (redis-stream {:host "localhost"})]
    (forward-probes {:host "localhost"})
    (activate-probe "test" client stream)))
