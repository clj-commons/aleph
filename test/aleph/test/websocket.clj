;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.websocket
  (:use
    [clojure.test]
    [lamina core connections]
    [aleph.http]
    [aleph.test.utils]))

(defmacro with-server [server & body]
  `(let [kill-fn# ~server]
     (try
       ~@body
       (finally
	 (kill-fn#)))))

(defmacro with-handler [handler & body]
  `(with-server (start-http-server ~handler
		  {:port 8008
                   :websocket true
		   })
     ~@body))

(defmacro with-rejecting-handler [handler & body]
  `(with-server (start-http-server ~handler
		  {:port 8008
                   :websocket true
                   :websocket-handshake-handler (fn [ch# _#] (enqueue ch# {:status 404}))
		   })
     ~@body))

(defn echo-handler [ch req]
  (siphon ch ch))

(defn ws-client []
  (websocket-client {:url "ws://localhost:8008"}))

(deftest test-echo-handler
  (with-handler echo-handler
    (let [ch (wait-for-result (ws-client) 1000)]
      (dotimes [_ 100]
        (enqueue ch "a")
        (is (= "a" (wait-for-result (read-channel ch) 500))))
      (close ch))))

(deftest test-handshake-handler
  (with-rejecting-handler echo-handler
    (is (thrown? Exception (wait-for-result (ws-client) 1000)))))

;;;

(deftest ^:benchmark run-echo-benchmark 
  (with-handler echo-handler
    (let [create-conn #(deref (websocket-client {:url "ws://localhost:8008"}))]
      
      (let [ch (create-conn)]
        (bench "websocket echo request"
          (enqueue ch "a")
          @(read-channel ch))
        (close ch))
      
      (let [c (pipelined-client (constantly (create-conn)))]
        (bench "batched websocket echo requests"
          @(apply merge-results (repeatedly 1e3 #(c "a"))))
        (close-connection c)))))
