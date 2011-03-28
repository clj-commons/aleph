(ns aleph.test.udp
  (:use
    [lamina.core]
    [aleph udp]
    [clojure.test]
    [gloss core]
    [clojure.set :only (difference)]))

(deftest send-recv-test
  (let [a (wait-for-result (udp-object-socket {:port 2222}))
        b (wait-for-result (udp-object-socket {}))
        c (wait-for-result (udp-socket {:frame (string :utf-8) :port 2223}))
        d (wait-for-result (udp-socket {:frame (string :utf-8)}))
        msg [{:a 1} "asdf" 23.3]] 
    (try
      (enqueue b {:message msg :host "localhost" :port 2222})
      (is (= msg (:message (first (channel-seq a 200)))))
      (enqueue d {:message "testing 1, 2, 3" :host "localhost" :port 2223})
      (is (= "testing 1, 2, 3" (:message (first (channel-seq c 200)))))
      (finally
        (close a)
        (close b)
        (close c)
        (close d)))))
