(ns aleph.tcp-test
  (:require
   [aleph.netty :as netty]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest is]]
   [manifold.stream :as s]))

(netty/leak-detector-level! :paranoid)

(defn echo-handler [s _]
  (s/connect s s))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(deftest test-echo
  (with-server (tcp/start-server echo-handler {:port 10001})
    (let [c @(tcp/client {:host "localhost", :port 10001})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))
