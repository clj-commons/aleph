;;   Copyright (c) Jeff Rose, Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.traffic
  (:use
    [lamina.core]
    [gloss.core]
    [aleph tcp formats traffic]
    [clojure test stacktrace]))

(def server-messages (ref []))

(defn append-to-server [msg]
  (dosync (alter server-messages conj (when msg (str msg)))))

(deftest monitor-test
  [])

(deftest traffic-test
  (dosync
    (ref-set server-messages []))
  (let [server-monitor (traffic-monitor)
        client-monitor (traffic-monitor)
        server (start-tcp-server
		 (fn [ch _]
		   (receive-all ch
		     (fn [x]
		       (when x
			 (append-to-server x)
			 (enqueue ch x)))))
                 {:frame (string :utf-8 :delimiters ["\0"])
                  :port 8888
                  :traffic-monitor server-monitor})]
    (try
      (let [
            ch (wait-for-result
                 (tcp-client {:host "localhost"
                              :port 8888
                              :frame (string :utf-8 :delimiters ["\0"])
                              :traffic-monitor client-monitor})
                 1000)]
        (dotimes [i 500]
          (enqueue ch "foo"))
	(let [s (doall (map str (take 1000 (lazy-channel-seq ch 1000))))]
	  (is (= s (map str @server-messages))))
        (is (= @(:total-bytes-read client-monitor)
               @(:total-bytes-written client-monitor)
               @(:total-bytes-read server-monitor)
               @(:total-bytes-written server-monitor))))
      (finally
        (stop-traffic-monitor server-monitor)
        (stop-traffic-monitor client-monitor)
	(server)
        (println "Server Monitor:" (traffic-report server-monitor))
        (println "Client Monitor:" (traffic-report client-monitor))))))

