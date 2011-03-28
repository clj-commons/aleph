;;   Copyright (c) Jeff Rose, Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

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
        c (wait-for-result (udp-socket {:frame (string :utf-8 :as-str true) :port 2223}))
        d (wait-for-result (udp-socket {:frame (string :utf-8 :as-str true)}))
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
