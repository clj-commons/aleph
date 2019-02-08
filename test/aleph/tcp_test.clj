(ns aleph.tcp-test
  (:use
    [clojure test])
  (:require
    [manifold.stream :as s]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [aleph.tcp :as tcp]))

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

(deftest test-echo-with-native-transport
  (with-server (tcp/start-server echo-handler {:port 10001
                                               :epoll? true
                                               :kqueue? true})
    (let [c @(tcp/client {:host "localhost"
                          :port 10001
                          :epoll? true
                          :kqueue? true})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))

(deftest test-echo-with-unix-socket
  (when (netty/native-transport-available?)
    (with-server (tcp/start-server echo-handler {:unix-socket "/tmp/aleph.sock"})
      (let [c @(tcp/client {:unix-socket "/tmp/aleph.sock"})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))))))
