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
    [lamina core connections executor]))

;;;

(defmacro with-server [handler port & body]
  `(let [stop-server# (start-tcp-server ~handler {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)))))

(defn basic-echo-handler [ch _]
  (siphon ch ch))

(defmacro with-stomp-connection [[c] port & body]
  `(let [~c (pipelined-client #(deref (stomp-connection {:host "localhost", :port ~port})))]
     (try
       ~@body
       (finally
         (close-connection ~c)))))

(defmacro with-stomp-client [[c] port & body]
  `(let [~c @(stomp-client {:host "localhost", :port ~port})]
     (try
       ~@body
       (finally
         (close-connection ~c)))))

(defmacro with-router [port & body]
  `(let [stop-server# (start-stomp-router {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)))))

;;;

(def messages
  [{:command :message
    :headers {"content-length" "10"
              "abc/def/bloop : \n \\" "42 \\ \n :"}
    :body "abcdefghij"}
   {:command :subscribe
    :headers {}
    :body "abcdefghij"}
   {:command :heartbeat}])

(deftest test-basic-echo
  (with-server basic-echo-handler 10001
    (with-stomp-connection [c] 10001
      (doseq [m (->> messages
                  (repeat 10)
                  (apply concat))]
        (is (= m @(c m 1000)))))))

;;;

(deftest test-basic-router
  (with-router 10001
    (with-stomp-client [c1] 10001
      (let [{a :messages} (subscribe c1 "abc")
            {b :messages} (subscribe c1 "def")
            consume #(-> % read-channel (wait-for-result 2500) :body read-string)]

        ;; wait for subscriptions to propagate
        (Thread/sleep 250)

        (publish c1 "abc" 1)
        (publish c1 "def" 2)
        (is (= 1 (consume a)))
        (is (= 2 (consume b)))

        ;; unsubscribe, and wait to propagate
        (close a)
        
        (publish c1 "def" 4)
        (is (= 4 (consume b)))

        (with-stomp-client [c2] 10001

          (publish c2 "def" 5)
          (is (= 5 (consume b)))
          
          (let [{c :messages} (subscribe c2 "def")]
            
            (Thread/sleep 250)

            (publish c1 "def" 6)
            (publish c2 "def" 7)
            (is (= #{6 7} (set [(consume b) (consume b)])))
            (is (= #{6 7} (set [(consume c) (consume c)])))))))))

;;;

(deftest ^:benchmark test-stomp-roundtrip
  (let [msg (first messages)]
    (with-server basic-echo-handler 10001
      (with-stomp-connection [c] 10001
        (bench "simple stomp roundtrip"
          @(c msg))
        (bench "1e3 stomp roundtrips"
          @(apply merge-results (repeatedly 1e3 #(c msg))))))))

(deftest ^:benchmark test-router-roundtrip
  (with-router 10001
    (with-stomp-client [c] 10001
      (let [{ch :messages} (subscribe c "abc")]
        (Thread/sleep 500)
        (bench "stomp router roundtrip"
          (publish c "abc" 1)
          @(read-channel ch))
        (bench "1e3 stomp router roundtrip"
          (let [f (task
                    (dotimes [_ 1e3]
                      @(read-channel ch)))]
            (dotimes [_ 1e3]
              (publish c "abc" 1))
            @f))))))
