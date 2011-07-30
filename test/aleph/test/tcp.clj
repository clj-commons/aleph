;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.tcp
  (:use
    [lamina.core]
    [gloss.core]
    [aleph tcp formats]
    [clojure.test]
    [clojure.set :only (difference)]))

(def server-messages (atom []))
(def server-write-results (atom []))
(def client-write-results (atom []))

(defn append-to-server [msg]
  (swap! server-messages conj (when msg (str msg))))

(defmacro tcp-test [& body]
  `(do
     (reset! server-messages [])
     (reset! server-write-results [])
     (reset! client-write-results [])
     ~@body))

(deftest echo-server
  (tcp-test
    (let [server (start-tcp-server
		   (fn [ch _]
		     (receive-all ch
		       (fn [x]
			 (when x
			   (append-to-server x)
			   (swap! server-write-results conj (enqueue ch x))))))
		   {:frame (string :utf-8 :delimiters ["\0"])
		    :port 8888})]
      (try
	(let [ch (wait-for-result
		   (tcp-client {:host "localhost"
				:port 8888
				:frame (string :utf-8 :delimiters ["\0"])})
		   1000)]
	  (doseq [[i j] (partition 2 (range 1000))]
	    (swap! client-write-results conj (enqueue ch (str i) (str j))))
	  (let [s (doall (map str (take 1000 (lazy-channel-seq ch 1000))))]
	    (is (= s (map str @server-messages)))
	    (is (every? true? (map deref @server-write-results)))
	    (is (every? true? (map deref @client-write-results)))))
	(finally
	  (server))))))

(deftest client-enqueue-and-close
  (tcp-test
    (let [server (start-tcp-server
		   (fn [ch _]
		     (receive-all ch
		       (fn [x]
			 (append-to-server x))))
		   {:port 8888
		    :frame (string :utf-8)
		    :delimiters ["x"]})]
      (try
	(let [ch (wait-for-result
		   (tcp-client {:host "localhost"
				:port 8888
				:frame (string :utf-8)
				:delimiters ["x"]})
		   1000)]
	  (enqueue ch "a")
	  (enqueue ch "b")
	  (enqueue-and-close ch "c")
	  (Thread/sleep 500)
	  (is (= ["a" "b" "c" nil] @server-messages)))
	(finally
	  (server))))))

(deftest server-enqueue-and-close
  (let [server (start-tcp-server
		 (fn [ch _]
		   (enqueue-and-close ch "a"))
		 {:frame (string :utf-8)
		  :port 8888})]
    (try
      (let [ch (wait-for-result
		 (tcp-client {:host "localhost" :port 8888 :frame (string :utf-8)})
		 1000)]
	(is (= ["a"] (doall (map str (channel-seq ch 1000)))))
	(is (drained? ch)))
      (finally
	(server)))))

