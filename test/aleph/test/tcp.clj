;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.tcp
  (:use
    [aleph.test.utils]
    [clojure.test]
    [lamina core connections]
    [aleph tcp formats]))

(defmacro with-server [handler port & body]
  `(let [stop-server# (start-tcp-server ~handler {:port ~port})]
     (try
       ~@body
       (finally
         (stop-server#)))))

(defn echo-handler [ch _]
  (siphon ch ch))

(deftest test-echo-server
  (with-server echo-handler 10000
    (let [c (comp
              bytes->string
              deref
              (client #(deref (tcp-client {:host "localhost", :port 10000}))))]
      (dotimes [_ 10]
        (is (= "a" (c "a")))))))

(deftest ^:benchmark test-echo-server
  (with-server echo-handler 10000
    (let [ch @(tcp-client {:host "localhost", :port 10000})
          c (client (constantly @(tcp-client {:host "localhost", :port 10000})))
          cl (comp bytes->string deref c)
          p-c (pipelined-client (constantly @(tcp-client {:host "localhost", :port 10000})))
          p-cl (comp bytes->string deref p-c)]

      (bench "single tcp client echo"
        (enqueue ch "a")
        @(read-channel ch))
      (bench "single tcp client echo w/ lamina.connections/client"
        (cl "a"))
      (bench "single tcp client echo w/ lamina.connections/pipelined-client"
        (p-cl "a"))

      (close-connection p-c)
      (close-connection c)
      (close ch))

    (bench "multiple tcp clients echo"
      (let [ch @(tcp-client {:host "localhost", :port 10000})]
        (enqueue ch "a")
        @(read-channel ch)
        (close ch)))

    (bench "multiple tcp clients echo w/ lamina.connections/client"
      (let [ch @(tcp-client {:host "localhost", :port 10000})
            c (client (constantly ch))
            cl (comp bytes->string deref c)]
        (cl "a")
        (close-connection c)))

    (bench "multiple tcp clients echo w/ lamina.connections/pipelined-client"
      (let [ch @(tcp-client {:host "localhost", :port 10000})
            c (pipelined-client (constantly ch))
            cl (comp bytes->string deref c)]
        (cl "a")
        (close-connection c)))))
