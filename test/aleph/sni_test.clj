(ns aleph.sni-test
  (:use [clojure test])
  (:require
   [aleph.tcp-ssl-test :as ssl-test]
   [aleph.http-test :refer [with-server]]
   [aleph.http :as http]
   [aleph.netty :as netty]
   [manifold.deferred :as d])
  (:import
   [io.aleph.dirigiste IPool]))

(def port 8092)

(def ^:dynamic *sni-pool* nil)

(defn ok-handler [_]
  {:status 200
   :body "OK"})

(defmacro with-sni-pool [^IPool pool & body]
  `(binding [*sni-pool* ~pool]
     (let [ssl# ssl-test/server-ssl-context
           default-ssl# (netty/self-signed-ssl-context)]
       (with-server (http/start-server ok-handler
                                       {:port port
                                        :ssl-context {"aleph.io.local" ssl#
                                                      "*.netty.io.local" ssl#
                                                      "*" default-ssl#}})
         ~@body)
       (.shutdown ~pool))))

(defn get-status [host]
  (-> (http/get (str "https://" host ":" port) {:pool *sni-pool*})
      (d/chain' :status)))

(deftest test-sni-handler-with-manual-peer
  (with-sni-pool (http/connection-pool
                  {:connection-options
                   {:sni {:peer-host "aleph.io.local"}
                    :ssl-context ssl-test/client-ssl-context}})
    (is (= 200 @(get-status "127.0.0.1")))))

(deftest test-sni-handler-resolved-host
  (with-sni-pool (http/connection-pool
                  {:connection-options
                   {:name-resolver (netty/static-name-resolver
                                    {"aleph.io.local" "127.0.0.1"
                                     "docs.netty.io.local" "127.0.0.1"})
                    :ssl-context ssl-test/client-ssl-context}})
    (testing "one-to-one match"
      (is (= 200 @(get-status "aleph.io.local"))))

    (testing "wildcard support"
      (is (= 200 @(get-status "docs.netty.io.local"))))))

(deftest test-sni-handler-unrecognized-host
  (with-sni-pool (http/connection-pool
                  {:connection-options
                   {:ssl-context ssl-test/client-ssl-context}})
    (is (thrown? Exception @(get-status "127.0.0.1")))))

(deftest test-disable-sni-setting
  (with-sni-pool (http/connection-pool
                  {:connection-options
                   {:sni :none
                    :name-resolver (netty/static-name-resolver
                                    {"aleph.io.local" "127.0.0.1"})
                    :ssl-context ssl-test/client-ssl-context}})
    (is (thrown? Exception @(get-status "aleph.io.local")))))
