;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.object
  (:use
    [aleph object]
    [clojure.test]
    [lamina.core]))

(def server-messages (atom []))

(defn append-to-server [msg]
  (swap! server-messages conj msg))

(deftest echo-server
  (reset! server-messages [])
  (let [server (start-object-server
		 (fn [ch _]
		   (receive-all ch
		     (fn [x]
		       (when x
			 (enqueue ch x)
			 (append-to-server x)))))
		 {:port 8888})]
    (try
      (let [ch (wait-for-result (object-client
				  {:host "localhost"
				   :port 8888
				   :probes {:errors nil-channel}}))]
	(dotimes [i 1000]
	  (enqueue ch [i]))
	(let [s (doall (lazy-channel-seq ch 1000))]
	  (is (= s @server-messages)))
	(close ch))
      (finally
	(server)))))
