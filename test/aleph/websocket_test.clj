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
    (d/catch (fn [e] {}))))

(deftest test-echo-handler
  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (s/put! c "hello")
      (is (= "hello" @(s/try-take! c 5e3))))
    (is (= 400 (:status @(http/get "http://localhost:8080" {:throw-exceptions false})))))

  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (s/put! c "hello with compression enabled")
      (is (= "hello with compression enabled" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (s/put! c "hello")
      (is (= "hello" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (s/put! c "hello compressed")
      (is (= "hello compressed" @(s/try-take! c 5e3))))))

(deftest test-ping-pong-protocol
  (testing "empty ping from the client"
    (with-handler #(http/websocket-connection %)
      (let [c @(http/websocket-client "ws://localhost:8080")]
        (is (true? (deref (http/websocket-ping c) 5e3 ::timeout))))))

  (testing "empty ping from the server"
    (let [d' (d/deferred)]
      (with-handler (fn [req]
                      (d/chain'
                       (http/websocket-connection req)
                       (fn [conn]
                         (d/chain'
                          (http/websocket-ping conn)
                          (partial d/success! d')))))
        @(http/websocket-client "ws://localhost:8080")
        (is (true? (deref d' 5e3 ::timeout))))))

  (testing "ping with payload from the client"
    (with-handler #(http/websocket-connection %)
      (let [d' (d/deferred)
            c @(http/websocket-client "ws://localhost:8080")]
        (is (true? (deref (http/websocket-ping c d' "hello!") 5e3 ::timeout))))))

  (testing "concurrent pings from the client"
    (with-handler #(http/websocket-connection %)
      (let [c @(http/websocket-client "ws://localhost:8080")
            all-pings (->> (range 10)
                           (map (fn [_]
                                  (-> (http/websocket-ping c)
                                      (d/timeout! 1e3))))
                           (apply d/zip'))]
        (is (= (repeat 10 true) (deref all-pings 5e3 ::timeout)))))))
