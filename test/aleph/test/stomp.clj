;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.stomp
  (:use
    [clojure test]
    [aleph stomp tcp]
    [aleph.test utils]
    [lamina core connections]))

;;;

(defmacro with-server [handler port & body]
  `(let [stop-server# (start-tcp-server ~handler {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)))))

(defn basic-echo-handler [ch _]
  (siphon ch ch))

(defmacro with-stomp-client [[c] port & body]
  `(let [~c (pipelined-client #(deref (stomp-connection {:host "localhost", :port ~port})))]
     (try
       ~@body
       (finally
         (close-connection ~c)))))

;;;

(def messages
  [{:command :message
    :headers {"content-length" "10"
              "abc/def/bloop" "42"}
    :body "abcdefghij"}
   {:command :subscribe
    :headers {}
    :body "abcdefghij"}
   {:command :heartbeat}])

(deftest test-basic-echo
  (with-server basic-echo-handler 10000
    (with-stomp-client [c] 10000
      (doseq [m (->> messages
                  (repeat 10)
                  (apply concat))]
        (is (= m @(c m 1000)))))))

(deftest ^:benchmark test-stomp-roundtrip
  (let [msg (first messages)]
    (with-server basic-echo-handler 10000
      (with-stomp-client [c] 10000
        (bench "simple stomp roundtrip"
          @(c msg))
        (bench "1e3 stomp roundtrips"
          @(apply merge-results (repeatedly 1e3 #(c msg))))))))
