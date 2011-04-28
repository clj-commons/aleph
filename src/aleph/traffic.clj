;;   Copyright (c) Jeff Rose, Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Jeff Rose"}
  aleph.traffic
  (:use
    [aleph netty]
    [lamina.core])
  (:require
    [clojure.contrib.logging :as log])
  (:import
    [org.jboss.netty.util
     DefaultObjectSizeEstimator]
    [org.jboss.netty.buffer
     ChannelBuffer]
    [java.nio
     ByteBuffer]
    [java.util.concurrent
     Executor]))

; TODO: Add message counters up and down too...

(def MIN-UPDATE-PERIOD 10)
(def DEFAULT-UPDATE-PERIOD 1000)

(defn- update-monitor-stats
  [{:keys [bytes-read bytes-read-window
           bytes-written bytes-written-window
           read-throughput write-throughput
           update-period monitor?] :as m}]
  (let [period (max @update-period MIN-UPDATE-PERIOD)
        br @bytes-read
        bw @bytes-written]
    (reset! bytes-read-window br)
    (reset! bytes-written-window bw)
    (swap! bytes-read - br)
    (swap! bytes-written - bw)
    (reset! read-throughput (* 1000.0 (/ br period)))
    (reset! write-throughput (* 1000.0 (/ bw period)))
    (when @monitor?
      (future
        (Thread/sleep period)
        (.submit ^Executor netty-thread-pool
                 #(update-monitor-stats m))))))

(def DEFAULT-ESTIMATOR (DefaultObjectSizeEstimator.))

(defn- smart-estimator
  [obj]
  (cond
    (instance? ChannelBuffer obj) (.readableBytes ^ChannelBuffer obj)
    :else (.estimateSize ^DefaultObjectSizeEstimator DEFAULT-ESTIMATOR obj)))

(defn traffic-monitor
  ([] (traffic-monitor {}))
  ([options]
   (let [{:keys [update-period estimator]} options
         m {:monitor?              (atom true)
            :update-period (atom (or update-period
                                     DEFAULT-UPDATE-PERIOD))
            :estimator (or estimator smart-estimator)
            :bytes-read           (atom 0)
            :bytes-read-window    (atom 0)
            :total-bytes-read     (atom 0)
            :read-throughput      (atom 0.0)
            :messages-read        (atom 0)

            :bytes-written        (atom 0)
            :bytes-written-window (atom 0)
            :total-bytes-written  (atom 0)
            :write-throughput     (atom 0.0)
            :messages-written     (atom 0)
            }]
     (update-monitor-stats m)
     m)))

(defn traffic-monitored-pipeline
  [pipeline monitor]
  (let [{:keys [estimator bytes-read total-bytes-read
                bytes-written total-bytes-written
                messages-read messages-written]} monitor
        read-monitor
        (upstream-stage
          (fn [evt]
            (if-let [msg (message-event evt)]
              (let [size (estimator msg)]
                (swap! messages-read inc)
                (swap! bytes-read + size)
                (swap! total-bytes-read + size)))
            false))
        write-monitor
        (downstream-stage
          (fn [evt]
            (if-let [msg (message-event evt)]
              (let [size (estimator msg)]
                (swap! messages-written inc)
                (swap! bytes-written + size)
                (swap! total-bytes-written + size)))
            false))]
    (.addFirst pipeline "read-traffic-monitor" read-monitor)
    (.addFirst pipeline "write-traffic-monitor" write-monitor)
    pipeline))

(defn reset-traffic-monitor
  [m]
  (let [counters (select-keys m
                              :messages-read
                              :messages-written
                              :bytes-read
                              :bytes-read-window
                              :total-bytes-read
                              :read-throughput
                              :bytes-written
                              :bytes-written-window
                              :total-bytes-written
                              :write-throughput)]
    (doseq [ctr (vals counters)]
      (reset! ctr 0))))

(defn start-traffic-monitor
  [m]
  (reset! (:monitor? m) true)
  (update-monitor-stats m))

(defn stop-traffic-monitor
  [m]
  (reset! (:monitor? m) false))

(defn traffic-report
  [{:keys [total-bytes-read total-bytes-written
           messages-read messages-written
           read-throughput write-throughput
           update-period monitor?]}]

  (format
"
 ----------------------------------
| %22s: %8b |
| %22s: %8d |
| %22s: %8d |
| %22s: %8d |
| %22s: %8d |
| %22s: %8d |
| %22s: %8.2f |
| %22s: %8.2f |
 ----------------------------------
"
    "monitor?" @monitor?
    "update-period" @update-period
    "messages-read" @messages-read
    "messages-written" @messages-written
    "total-bytes-read" @total-bytes-read
    "total-bytes-written" @total-bytes-written
    "read-throughput" @read-throughput
    "write-throughput" @write-throughput))
