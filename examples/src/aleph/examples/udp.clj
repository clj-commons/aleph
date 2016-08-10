(ns aleph.examples.udp
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.udp :as udp]
    [clojure.string :as str]
    [byte-streams :as bs]))

;; Full documentation for the `aleph.udp` namespace can be found [here](http://aleph.io/codox/aleph/aleph.udp.html).

;; This example is a very pared-down version of [statsd](https://github.com/etsy/statsd), which
;; takes in UDP packets from the entire system, and does periodic rollups that are typically
;; forwarded to [Graphite](http://graphite.wikidot.com/).

(def server-port 10002)

;; This creates a socket without a port, which we can only use to send messages.  We dereference
;; this, since it will typically complete immediately.
(def client-socket @(udp/socket {}))

(defn send-metric!
  "This encodes a message in the typical statsd format, which is two strings, `metric` and
   `value`, delimited by a colon."
  [metric ^long value]
  (s/put! client-socket
    {:host "localhost"
     :port server-port
     ;; The UDP contents can be anything which byte-streams can coerce to a byte-array.  If
     ;; the combined length of the metric and value were to exceed 65536 bytes, this would
     ;; fail, and `send-metrics!` would return a deferred value that yields an error.
     :message (str metric ":" value)}))

(defn parse-statsd-packet
  "This is the inverse operation of `send-metrics!`, taking the message, splitting it on the
   colon delimiter, and parsing the `value`."
  [{:keys [message]}]
  (let [message        (bs/to-string message)
        [metric value] (str/split message #":")]
    [metric (Long/parseLong value)]))

(defn get-and-set!
  "An atomic operation which returns the previous value, and sets it to `new-val`."
  [a new-val]
  (let [old-val @a]
    (if (compare-and-set! a old-val new-val)
      old-val
      (recur a new-val))))

(defn start-statsd-server
  []
  (let [accumulator   (atom {})
        server-socket @(udp/socket {:port server-port})
        ;; Once a second, take all the values that have accumulated, `put!` them out, and
        ;; clear the accumulator.
        metric-stream (s/periodically 1000 #(get-and-set! accumulator {}))]

    ;; Listens on a socket, parses each incoming message, and increments the appropriate metric.
    (->> server-socket
      (s/map parse-statsd-packet)
      (s/consume
        (fn [[metric value]]
          (swap! accumulator update metric #(+ (or % 0) value)))))

    ;; If `metric-stream` is closed, close the associated socket.
    (s/on-drained metric-stream #(s/close! server-socket))

    metric-stream))

(def server (start-statsd-server))

(send-metric! "a" 1)
(send-metric! "b" 2)

@(s/take! server)     ; => {"a" 1, "b" 2}

(s/close! server)
