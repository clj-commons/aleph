(ns aleph.name-resolver-test
  (:use [clojure test])
  (:require
   [aleph.http-test :refer [with-server]]
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.deferred :as d])
  (:import
   [aleph.utils UnrecognizedHostException]
   [io.aleph.dirigiste IPool]))

(def port 8072)

(def ^:dynamic ^IPool *named-pool* nil)

(netty/leak-detector-level! :paranoid)

(defn basic-handler [_]
  {:status 200
   :body "OK"})

(defmacro with-basic-handler [& body]
  `(let [resolver# (netty/static-name-resolver {"aleph.io" "127.0.0.1"
                                                "*.netty.io" "127.0.0.1"})]
     (binding [*named-pool* (http/connection-pool
                             {:connection-options {:name-resolver resolver#}})]
       (with-server (http/start-server basic-handler {:port port})
         (try
           ~@body
           (finally
             (.shutdown *named-pool*)))))))

(defn get-status [host]
  (-> (http/get (str "http://" host ":" port) {:pool *named-pool*})
      (d/chain' :status)))

(deftest test-static-name-resolver
  (with-basic-handler
    (testing "success resolve"
      (is (= 200 @(get-status "aleph.io"))))

    (testing "wildcard support"
      (is (= 200 @(get-status "docs.netty.io")))
      (is (= 200 @(get-status "downloads.netty.io"))))

    (testing "unknown host"
      (is (thrown? UnrecognizedHostException
                   @(get-status "google.com"))))))
