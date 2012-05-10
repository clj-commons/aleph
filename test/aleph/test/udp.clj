;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.udp
  (:use
    [clojure test]
    [lamina core connections]
    [gloss core]
    [aleph udp]
    [aleph.test.utils]))

(deftest send-recv-test
  (let [a @(udp-object-socket {:port 2222})
        b @(udp-object-socket)
        c @(udp-socket {:frame (string :utf-8) :port 2223})
        d @(udp-socket {:frame (string :utf-8)})
        e @(udp-socket)
        object-msg [{:a 1} "asdf" 23.3]
	text-msg (->> "a" (repeat 1e3) (apply str))] 
    (try
      (enqueue b {:message object-msg :host "localhost" :port 2222})
      (is (= object-msg (:message (wait-for-message a 2000))))
      (enqueue d {:message text-msg :host "localhost" :port 2223})
      (is (= text-msg (:message (wait-for-message c 2000))))
      (enqueue e {:message text-msg :host "localhost" :port 2223})
      (is (= text-msg (:message (wait-for-message c 2000))))
      (finally
        (close b)
        (close d)
        (close e)
        (close a)
        (close c)))))

;;;

(defn echo-udp-socket [port]
  (let [ch @(udp-socket {:port port})]
    (siphon ch ch)
    ch))

(deftest ^:benchmark benchmark-udp
  (let [ch (echo-udp-socket 10000)]
    (try
      (let [create-conn #(deref (udp-socket))]
        
        (let [ch (create-conn)]
          (bench "udp echo request"
            (enqueue ch {:host "localhost", :port 10000, :message "a"})
            @(read-channel ch))
          (close ch)))
      (finally
        (close ch)))))
