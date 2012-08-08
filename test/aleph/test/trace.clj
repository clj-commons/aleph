;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.trace
  (:use
    [clojure test]
    [aleph trace]
    [aleph.test utils]
    [lamina core connections trace executor])
  (:require
    [aleph.formats :as formats]))

;;;

(defmacro with-router [port & body]
  `(let [stop-server# (start-trace-router {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)))))

(defmacro with-endpoint [[e] port & body]
  `(let [~e (trace-endpoint {:aggregation-period 100 :client-options {:host "localhost", :port ~port}})]
     (try
       ~@body
       (finally
         (close-connection ~e)))))

;;;



(deftest test-basic-routing
  (with-router 10000
    (with-endpoint [c1] 10000
      (let [a (subscribe c1 "abc")
            b (subscribe c1 "def")
            consume #(-> % read-channel (wait-for-result 2500))]

        ;; wait for subscriptions to propagate
        (Thread/sleep 500)

        (is (= true (trace :abc 1)))
        (is (= true (trace :def 2)))
        (is (= 1 (consume a)))
        (is (= 2 (consume b)))

        ;; unsubscribe, and wait to propagate
        (close a)
        (Thread/sleep 500)
        
        (is (= false (trace :abc 3)))
        (is (= true (trace :def 4)))
        (is (= 4 (consume b)))

        (with-endpoint [c2] 10000

          ;; wait for existing subscriptions to propagate
          (Thread/sleep 500)

          ;; this should double broadcast
          (is (= true (trace :def 5)))
          (is (= 5 (consume b)))
          (is (= 5 (consume b))))))
    
    (Thread/sleep 500)
    (is (= false (trace :def 6)))))
