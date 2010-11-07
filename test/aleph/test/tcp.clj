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
    [aleph tcp formats]
    [clojure.test]))

(def server-messages (ref []))

(defn append-to-server [msg]
  (dosync (alter server-messages conj (byte-buffer->string msg))))

(defn join-and-split [s]
  (seq (.split (apply str s) "\0")))

(deftest echo-server
  (dosync
    (ref-set server-messages []))
  (let [server (start-tcp-server
		 (fn [ch _]
		   (receive-all ch
		     (fn [x]
		       (when x
			 (enqueue ch x)
			 (append-to-server x)))))
		 {:port 8888})]
    (try
      (let [ch (wait-for-pipeline (tcp-client {:host "localhost" :port 8888}))]
	(dotimes [i 1000]
	  (enqueue ch (str i "\0")))
	(let [s (doall (lazy-channel-seq ch 100))]
	  (is (=
	       (join-and-split (map byte-buffer->string s))
	       (join-and-split @server-messages)))))
      (finally
	(server)))))

(deftest client-enqueue-and-close
  (dosync
    (ref-set server-messages []))
  (let [server (start-tcp-server
		 (fn [ch _]
		   (receive-all ch
		     (fn [x]
		       (append-to-server x))))
		 {:port 8888
		  :delimiters ["x"]})]
    (try
      (let [ch (wait-for-pipeline (tcp-client {:host "localhost" :port 8888}))]
	(enqueue ch "ax")
	(enqueue ch "bx")
	(enqueue-and-close ch "cx")
	(Thread/sleep 100)
	(is (= ["a" "b" "c" nil] @server-messages)))
      (finally
	(server)))))

(deftest server-enqueue-and-close
  (let [server (start-tcp-server
		 (fn [ch _]
		   (enqueue-and-close ch "a"))
		 {:port 8888})]
    (try
      (let [ch (wait-for-pipeline (tcp-client {:host "localhost" :port 8888}))]
	(is (= ["a"] (map #(when % (byte-buffer->string %)) (channel-seq ch -1))))
	(is (closed? ch)))
      (finally
	(server)))))

