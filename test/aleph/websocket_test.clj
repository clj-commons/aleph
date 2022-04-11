(ns aleph.websocket-test
  (:use
    [clojure test])
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [manifold.time :as time]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [aleph.http :as http]
    [aleph.http.core :as http-core]
    [aleph.http.server :as http-server]
    [clojure.tools.logging :as log]))

(netty/leak-detector-level! :paranoid)

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

(defn connection-handler
  ([req] (connection-handler {} req))
  ([options req]
   (if (http-server/websocket-upgrade-request? req)
     (-> (http/websocket-connection req options)
         (d/chain' #(s/connect % %))
         (d/catch'
          (fn [^Throwable e]
            (log/error "upgrade to websocket conn failed"
                       (.getMessage e))
            {}))))))

(defn echo-handler
  ([req] (echo-handler {} req))
  ([options req]
   (-> (http/websocket-connection req options)
       (d/chain' #(s/connect % %))
       (d/catch'
           (fn [^Throwable e]
             (log/error "upgrade to websocket conn failed"
                        (.getMessage e))
             {})))))

(defn raw-echo-handler [req]
  (-> (http/websocket-connection req {:raw-stream? true})
    (d/chain' #(s/connect % %))
    (d/catch'
        (fn [^Throwable e]
          (log/error "upgrade to websocket conn failed"
                     (.getMessage e))
          {}))))

(deftest test-connection-header
  (with-handler connection-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3)))
      (is (= "upgrade" (get-in (s/description c) [:sink :websocket-handshake-headers "connection"]))))
    (let [c @(http/websocket-client "ws://localhost:8080" {:headers {:connection "keep-alive, Upgrade"}})]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3)))
      (is (= "upgrade" (get-in (s/description c) [:sink :websocket-handshake-headers "connection"]))))
    (is (= 204 (:status @(http/get "http://localhost:8080"
                                   {:throw-exceptions false})))))
  )

(deftest test-echo-handler
  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3)))
      (is (= "upgrade" (get-in (s/description c) [:sink :websocket-handshake-headers "connection"]))))
    (is (= 400 (:status @(http/get "http://localhost:8080"
                                   {:throw-exceptions false})))))

  (with-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (is @(s/put! c "hello with compression enabled"))
      (is (= "hello with compression enabled" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is @(s/put! c "hello"))
      (is (= "hello" @(s/try-take! c 5e3)))))

  (with-compressing-handler echo-handler
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (is @(s/put! c "hello compressed"))
      (is (= "hello compressed" @(s/try-take! c 5e3))))))

(deftest test-per-message-compression-handler
  (with-handler (partial echo-handler {:compression? true})
    (let [c @(http/websocket-client "ws://localhost:8080" {:compression? true})]
      (is (= "permessage-deflate" (get-in (s/description c)
                                          [:sink
                                           :websocket-handshake-headers
                                           "sec-websocket-extensions"])))
      (is @(s/put! c "hello with per-message deflate enabled"))
      (is (= "hello with per-message deflate enabled" @(s/try-take! c 5e3))))))

(deftest test-server-handshake-description
  (with-handler (fn [req]
                  (-> (http/websocket-connection req)
                      (d/chain'
                       (fn [s]
                         (let [desc (:sink (s/description s))
                               c (contains? desc :websocket-selected-subprotocol)]
                           (s/put! s (if c "YES" "NO"))
                           (s/close! s))))))
    (let [c @(http/websocket-client "ws://localhost:8080")]
      (is (= "YES" @(s/try-take! c 5e3))))))

(deftest test-raw-echo-handler
  (testing "websocket client: raw-stream? with binary message"
    (with-handler echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080" {:raw-stream? true})]
        (is @(s/put! c (.getBytes "raw client hello" "UTF-8")))
        (let [msg @(s/try-take! c 5e3)]
          (is (= "raw client hello"
                 (when msg (bs/to-string (netty/release-buf->array msg)))))))))

  (testing "websocket client: raw-stream? with text message"
    (with-handler echo-handler
      (let [c @(http/websocket-client "ws://localhost:8080" {:raw-stream? true})]
        (is @(s/put! c "text client hello"))
        (let [msg @(s/try-take! c 5e3)]
          (is (= "text client hello"
                 (when msg (bs/to-string msg))))))))

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
        (let [msg @(s/try-take! c 5e3)]
          (is (= "raw conn string hello" (when msg (bs/to-string msg)))))))))

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

(defmacro with-closed [client & body]
  `(let [closed# (d/deferred)]
     (s/on-closed ~client (fn [] (d/success! closed# true)))
     @closed#
     ~@body))

(deftest test-client-connection-close
  (with-handler echo-handler
    (let [closed (d/deferred)
          conn @(http/websocket-client "ws://localhost:8080")]
      (s/on-closed conn #(d/success! closed true))
      @(s/put! conn "message #1")
      @(s/put! conn "message #2")
      (let [cp (http/websocket-close! conn 4009 "back to roots")]
        @closed
        (is @cp "reported closed")
        (is (false? @(http/websocket-close! conn)) "subsequent close")))))

(deftest test-server-connection-close
  (testing "normal close"
    (let [handshake-started (d/deferred)
          subsequent-write (d/deferred)
          subsequent-close (d/deferred)]
      (with-handler
        (fn [req]
          (d/chain'
           (http/websocket-connection req)
           (fn [conn]
             (d/chain'
              (http/websocket-close! conn 4001 "going away")

              (fn [r]
                (d/success! handshake-started r)
                nil)

              (fn [_]
                (d/chain'
                 (s/put! conn "Any other message")
                 #(d/success! subsequent-write %)))

              (fn [_]
                (d/chain'
                 (http/websocket-close! conn 4002 "going away again")
                 #(d/success! subsequent-close %)))))))
        (let [client @(http/websocket-client "ws://localhost:8080")]
          (with-closed client
            (is (true? @handshake-started) "normal close")
            (is (false? @subsequent-write) "subsequent writes are rejected")
            (is (false? @subsequent-close) "already closed")
            (let [{:keys [websocket-close-code
                          websocket-close-msg]}
                  (-> client s/description :sink)]
              (is (= 4001 websocket-close-code))
              (is (= "going away" websocket-close-msg))))))))

  (testing "rejected for closed stream"
    (with-handler
      (fn [req]
        (d/chain'
         (http/websocket-connection req)
         (fn [conn]
           (s/close! conn)
           (d/chain'
            (http/websocket-close! conn 4001 "going away attempt")
            #(is (false? %) "should not be accepted")))))
      (let [client @(http/websocket-client "ws://localhost:8080")]
        (with-closed client
          (let [{:keys [websocket-close-code
                        websocket-close-msg]}
                (-> client s/description :sink)]
            ;; `-1` means that no code was provided
            ;; Netty's internal implementation, nothing to do with RFCs
            (is (= http-core/close-empty-status-code websocket-close-code))
            (is (= "" websocket-close-msg)))))))

  (testing "concurrent close attempts"
    (let [attempts (d/deferred)]
      (with-handler
        (fn [req]
          (d/chain'
           (http/websocket-connection req)
           (fn [conn]
             (d/connect
              (->> (range 10)
                   (mapv #(time/in (inc (rand-int 1))
                                   (fn []
                                     (http/websocket-close! conn (+ % 4000)))))
                   (apply d/zip'))
              attempts))))
        (let [client @(http/websocket-client "ws://localhost:8080")]
          (with-closed client
            (is (= 1 (count (filter true? @attempts))))
            (let [{:keys [websocket-close-code
                          websocket-close-msg]}
                  (-> client s/description :sink)]
              (is (<= 4000  websocket-close-code 4010))
              (is (= "" websocket-close-msg)))))))))
