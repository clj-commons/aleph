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
  `(with-server (http/start-server ~handler {:port 8081, :raw-stream? true})
     ~@body))

(defmacro with-compressing-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080, :compression? true})
     ~@body))

(defmacro with-native-transport [handler & body]
  `(with-server (http/start-server ~handler {:port 8080
                                             :epoll? true
                                             :kqueue? true})
     ~@body))

(defn echo-handler [req]
  (-> (http/websocket-connection req)
    (d/chain #(s/connect % %))
    (d/catch (fn [e] (log/error "upgrade to websocket conn failed" e) {}))))

(defn raw-echo-handler [req]
  (-> (http/websocket-connection req {:raw-stream? true})
    (d/chain #(s/connect % %))
    (d/catch (fn [e] (log/error "upgrade to websocket conn failed" e) {}))))

(deftest test-echo-handler
  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3))))
    (is (= 400 (:status @(http/get "http://localhost:8080" {:throw-exceptions false})))))

  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (is @(s/put! c "hello with compression enabled"))
      (is (= "hello with compression enabled" @(s/try-take! c 5e3)))))

  (testing "websocket client: raw-stream?"
    (with-handler echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080" {:raw-stream? true})]
        (is @(s/put! c (.getBytes "raw client hello" "UTF-8")))
        (let [msg @(s/try-take! c 5e3)]
          (is (= "raw client hello" (when msg (bs/to-string (netty/buf->array msg)))))))))

  (testing "websocket server: raw-stream? with binary message"
    (with-handler raw-echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080")]
        (is @(s/put! c (.getBytes "raw conn bytes hello" "UTF-8")))
        (let [msg @(s/try-take! c 5e3)]
          (is (= "raw conn bytes hello" (when msg (bs/to-string msg))))))))

  (testing "websocket server: raw-stream? with string message"
    (with-handler raw-echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080")]
        (is @(s/put! c "raw conn string hello"))
        (is (= "raw conn string hello" @(s/try-take! c 5e3))))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (is @(s/put! c "hello compressed"))
      (is (= "hello compressed" @(s/try-take! c 5e3))))))

(when (netty/native-transport-available?)
  (deftest test-websocket-with-native-transport
    (with-native-transport
      (let [c @(http/websocket-client "ws://localhost:8080"
                                      {:epoll? true :kqueue? true})]
        (is @(s/put! c "hello with native transport"))
        (is (= "hello with native transport" @(s/try-take! c 5e3)))))))

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

(deftest test-echo-handler-with-raw-stream-server
  (with-raw-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8081")]
      (is @(s/put! c "hello raw handler"))
      (is (= "hello raw handler" @(s/try-take! c 5e3)))
      (is @(s/put! c "hello raw handler 2"))
      (is (= "hello raw handler 2" @(s/try-take! c 5e3))))
    (is (= 400 (:status @(http/get "http://localhost:8081" {:throw-exceptions false}))))))
