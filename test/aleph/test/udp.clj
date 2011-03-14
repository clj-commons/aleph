(ns aleph.test.udp
  (:use
    [lamina.core]
    [aleph udp]
    [clojure.test]
    [clojure.set :only (difference)]))

(deftest send-recv-test
  (let [a (wait-for-result (create-udp-object-socket :port 2222))
        b (wait-for-result (create-udp-object-socket))
        c (wait-for-result (create-udp-text-socket :port 2223))
        d (wait-for-result (create-udp-text-socket))
        msg [{:a 1} "asdf" 23.3]] 
    (try
      (enqueue b {:msg msg :host "localhost" :port 2222})
      (is (= msg (:msg (first (channel-seq a 200)))))
      (enqueue d {:msg "testing 1, 2, 3" :host "localhost" :port 2223})
      (is (= "testing 1, 2, 3" (:msg (first (channel-seq c 200)))))
      (finally
        (close a)
        (close b)
        (close c)
        (close d)))))
