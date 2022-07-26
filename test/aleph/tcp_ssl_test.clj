(ns aleph.tcp-ssl-test
  (:require [aleph.tcp-test :refer [with-server]]
            [aleph.tcp :as tcp]
            [aleph.ssl :as ssl]
            [aleph.netty :as netty]
            [clj-commons.byte-streams :as bs]
            [clojure.test :refer [deftest is]]
            [manifold.stream :as s])
  (:import [java.security.cert X509Certificate]))

(netty/leak-detector-level! :paranoid)

(set! *warn-on-reflection* false)

(defn ssl-echo-handler [ssl-session]
  (fn [s c]
    (s/connect
     ;; note we need to capture the SSL session *after* we start
     ;; reading data. Otherwise, the session might not be set up yet.
     (s/map (fn [msg]
              (reset! ssl-session (:ssl-session c))
              msg)
            s)
     s)))

(deftest test-ssl-echo
  (let [ssl-session (atom nil)]
    (with-server (tcp/start-server (ssl-echo-handler ssl-session)
                                   {:port 10001
                                    :ssl-context ssl/server-ssl-context})
      (let [c @(tcp/client {:host "localhost"
                            :port 10001
                            :ssl-context ssl/client-ssl-context})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))
        (is (some? @ssl-session) "SSL session should be defined")
        (is (= (.getSubjectDN ^X509Certificate ssl/client-cert)
               (.getSubjectDN ^X509Certificate (first (.getPeerCertificates @ssl-session)))))))))
