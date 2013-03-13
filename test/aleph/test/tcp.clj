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
    [lamina core connections time]
    [aleph tcp formats]
    [gloss core])
  (:require
    [aleph.netty :as netty])
  (:import
    [org.jboss.netty.channel ChannelException]))

(defmacro with-server [handler options & body]
  `(let [stop-server# (start-tcp-server ~handler ~options)]
     (try
       ~@body
       (finally
         (stop-server#)))))

(defn basic-echo-handler [ch _]
  (siphon ch ch))

(defn server-echo-handler [ch _]
  (server (fn [ch x] (enqueue ch x)) ch {}))

(defn pause-then-echo-handler [ch _]
  (netty/set-channel-readable ch false)
  (server-echo-handler ch nil)
  (run-pipeline nil
    (wait-stage 1000)
    (fn [_] (netty/set-channel-readable ch true))))

;;;

(def n 10)
(def port 10000)
(def default-options {:port port})


(defn test-echo-server [client-fn]
  (let [c (client-fn #(deref (tcp-client {:host "localhost", :port port, :frame (string :utf-8 :delimiters ["\n"])})))]
    (dotimes [_ n]
      (is (= "a" @(c "a" 5e3))))
    (dotimes [_ n]
      (is (= (repeat n "a") @(apply merge-results (repeatedly n #(c "a" 5e3))))))
    (close-connection c)))

(deftest test-start-server-with-invalid-host
  (is (thrown-with-msg?
        ChannelException
        #"Failed to bind.*github.com"
        (with-server basic-echo-handler {:host "github.com", :port port}))))

(deftest test-echo-servers
  (with-server basic-echo-handler default-options
    (test-echo-server client)
    (test-echo-server pipelined-client))
  (with-server server-echo-handler default-options
    (test-echo-server client)
    (test-echo-server pipelined-client))
  (with-server basic-echo-handler {:host "localhost" :port port}
    (test-echo-server client)))

(deftest test-backpressure
  (with-server pause-then-echo-handler default-options
    (let [start (now)
          _  (test-echo-server pipelined-client)
          end (now)]
      (is (< 1000 (- end start))))))

;;;


(defn run-echo-benchmark [frame?]
  (let [create-conn #(deref (tcp-client {:host "localhost", :port port, :frame (when % (string :utf-8 :delimiters ["\n"]))}))]

    (let [ch (create-conn frame?)]
      (bench "tcp echo request"
        (enqueue ch "a")
        @(read-channel ch))
      (close ch))
      
    (let [c (client (constantly (create-conn frame?)))]
      (bench "tcp echo request w/ lamina.connections/client"
        @(c "a"))
      (close-connection c))
      
    (let [c (pipelined-client (constantly (create-conn frame?)))]
      (bench "tcp echo request w/ lamina.connections/pipelined-client"
        @(c "a"))
      (close-connection c))

    (when frame?
      (let [c (pipelined-client (constantly (create-conn true)))]
        (bench "batched echo requests"
          @(apply merge-results (repeatedly 1e3 #(c "a"))))
        (close-connection c)))))

#_(deftest ^:benchmark benchmark-connect-and-query
  (with-server basic-echo-handler default-options

    (println "priming JIT for client connection")
    
    ;; we can't do a full benchmark run, since that exhausts ephemeral
    ;; ports.  Instead, do a manual warm-up before doing quick-benches.
    (dotimes [_ 20]
      (dotimes [_ 1e3]
        (let [ch @(tcp-client {:host "localhost", :port port})]
          (enqueue ch "a")
          @(read-channel ch)
          (close ch)))
      (Thread/sleep 2000))
    
    (quick-bench "tcp connect + echo request"
      (let [ch @(tcp-client {:host "localhost", :port port, :frame (string :utf-8)})]
        (enqueue ch "a")
        @(read-channel ch)
        (close ch)))))

(deftest ^:benchmark benchmark-echo-server
  (with-server basic-echo-handler default-options

    (println "\n=== basic with :frame")
    (run-echo-benchmark true)
    (println "\n=== basic without :frame")
    (run-echo-benchmark false)

    )

  (with-server server-echo-handler default-options
    (println "\n=== lamina.connections/server without :frame")
    (run-echo-benchmark false)))
