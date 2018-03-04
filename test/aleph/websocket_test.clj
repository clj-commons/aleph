(ns aleph.websocket-test
  (:use
    [clojure test])
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [aleph.http :as http]
    [clojure.tools.logging :as log]))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)
         (netty/wait-for-close server#)))))

(defmacro with-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080})
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080, :raw-stream? true})
     ~@body))

(defmacro with-compressing-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080, :compression? true})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

(defn echo-handler [req]
  (-> (http/websocket-connection req)
    (d/chain #(s/connect % %))
    (d/catch (fn [e] (log/error "upgrade to websocket conn failed" e) {}))))

(defn raw-echo-handler [req]
  (-> (http/websocket-connection req {:raw-stream? true})
    (d/chain #(s/connect (s/map (fn [c] (if (string? c) c (netty/acquire c))) %) %))
    (d/catch (fn [e] (log/error "upgrade to websocket conn failed" e) {}))))

(deftest test-echo-handler
  (with-both-handlers echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (s/put! c "hello")
      (is (= "hello" @(s/try-take! c 5e3))))
    (is (= 400 (:status @(http/get "http://localhost:8080" {:throw-exceptions false})))))

  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (s/put! c "hello with compression enabled")
      (is (= "hello with compression enabled" @(s/try-take! c 5e3)))))

  (testing "websocket client: raw-stream?"
    (with-handler echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080" {:raw-stream? true})]
        (s/put! c (.getBytes "raw client hello" "UTF-8"))
        (is (= "raw client hello" (bs/to-string (netty/buf->array @(s/try-take! c 5e3))))))))

  (testing "websocket server: raw-stream? with binary message"
    (with-handler raw-echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080")]
        (s/put! c (.getBytes "raw conn bytes hello" "UTF-8"))
        (is (= "raw conn bytes hello" (bs/to-string @(s/try-take! c 5e3)))))))

  (testing "websocket server: raw-stream? with string message"
    (with-handler raw-echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080")]
        (s/put! c "raw conn string hello")
        (is (= "raw conn string hello" @(s/try-take! c 5e3))))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (s/put! c "hello")
      (is (= "hello" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (s/put! c "hello compressed")
      (is (= "hello compressed" @(s/try-take! c 5e3))))))
