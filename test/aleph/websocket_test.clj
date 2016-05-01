(ns aleph.websocket-test
  (:use
    [clojure test])
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [aleph.http :as http]))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(defmacro with-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080})
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080, :raw-stream? true})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

(defn echo-handler [req]
  (-> (http/websocket-connection req)
    (d/chain #(s/connect % %))
    (d/catch (fn [e] {}))))

(deftest test-echo-handler
  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (s/put! c "hello")
      (is (= "hello" @(s/try-take! c 5e3))))
    (is (= 400 (:status @(http/get "http://localhost:8080" {:throw-exceptions false}))))))
