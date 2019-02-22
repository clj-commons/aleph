(ns aleph.tcp-test
  (:use
   [clojure test])
  (:require
   [manifold.stream :as s]
   [aleph.netty :as netty]
   [byte-streams :as bs]
   [aleph.tcp :as tcp]
   [clojure.java.io :as io]))

(netty/leak-detector-level! :paranoid)

(defn echo-handler [s _]
  (s/connect s s))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(def words (slurp "/usr/share/dict/words"))

(deftest test-echo
  (with-server (tcp/start-server echo-handler {:port 10001})
    (let [c @(tcp/client {:host "localhost", :port 10001})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))

(when (netty/native-transport-available?)
  (deftest test-echo-with-native-transport
    (with-server (tcp/start-server echo-handler {:port 10001
                                                 :epoll? true
                                                 :kqueue? true})
      (let [c @(tcp/client {:host "localhost"
                            :port 10001
                            :epoll? true
                            :kqueue? true})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))))))

(when (netty/native-transport-available?)
  (deftest test-echo-with-unix-socket
    (let [path "/tmp/aleph.sock"]
      (testing "path to socket file"
        (with-server (tcp/start-server echo-handler {:unix-socket path})
          (let [c @(tcp/client {:unix-socket path})]
            (s/put! c "foo")
            (is (= "foo" (bs/to-string @(s/take! c)))))))

      (testing "file descriptor"
        (let [fd (io/file path)]
          (with-server (tcp/start-server echo-handler {:unix-socket fd})
            (let [c @(tcp/client {:unix-socket fd})]
              (s/put! c "foo")
              (is (= "foo" (bs/to-string @(s/take! c)))))))))))
