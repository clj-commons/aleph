(ns aleph.tcp-ssl-test
  (:require
   [aleph.netty :as netty]
   [aleph.ssl :as ssl]
   [aleph.tcp :as tcp]
   [aleph.tcp-test :refer [with-server]]
   [clj-commons.byte-streams :as bs]
   [clojure.test :refer [deftest is]]
   [manifold.deferred :as d]
   [manifold.stream :as s])
  (:import
   (java.security.cert X509Certificate)
   (java.util.concurrent TimeoutException)))

(netty/leak-detector-level! :paranoid)

(set! *warn-on-reflection* false)

(defn ssl-echo-handler [ssl-session]
  (fn [s c]
    (reset! ssl-session (:ssl-session c))
    (s/connect s s)))

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

(deftest test-ssl-opts-echo
  (let [ssl-session (atom nil)]
    (with-server (tcp/start-server (ssl-echo-handler ssl-session)
                                   {:port 10001
                                    :ssl-context ssl/server-ssl-context-opts})
      (let [c @(tcp/client {:host "localhost"
                            :port 10001
                            :ssl-context ssl/client-ssl-context-opts})]
        (s/put! c "foo")
        (is (= "foo" (bs/to-string @(s/take! c))))
        (is (some? @ssl-session) "SSL session should be defined")
        (is (= (.getSubjectDN ^X509Certificate ssl/client-cert)
               (.getSubjectDN ^X509Certificate (first (.getPeerCertificates @ssl-session)))))))))

(deftest test-failed-ssl-handshake
  (let [ssl-session (atom nil)]
    (with-server (tcp/start-server (ssl-echo-handler ssl-session)
                                   {:port 10001
                                    :ssl-context ssl/server-ssl-context})
      (let [c @(tcp/client {:host "localhost"
                            :port 10001
                            :ssl-context (netty/ssl-client-context
                                          ;; Note the intentionally wrong private key here
                                          {:private-key ssl/server-key
                                           :certificate-chain (into-array X509Certificate [ssl/client-cert])
                                           :trust-store (into-array X509Certificate [ssl/ca-cert])})})]
        (is (nil? @(s/take! c)))
        (is (nil? @ssl-session) "SSL session should be undefined")))))

(deftest test-connection-close-during-ssl-handshake
  (let [ssl-session (atom nil)
        connection-closed (promise)
        notify-connection-closed #_:clj-kondo/ignore (netty/channel-handler
                                                      :channel-inactive
                                                      ([_ ctx]
                                                       (deliver connection-closed true)
                                                       (.fireChannelInactive ctx)))]
    (with-server (tcp/start-server (ssl-echo-handler ssl-session)
                                   {:port 10001
                                    :ssl-context ssl/server-ssl-context
                                    :pipeline-transform (fn [p]
                                                          (.addLast p notify-connection-closed))})
      (let [c @(tcp/client {:host "localhost"
                            :port 10001})]
        (s/close! c)
        (is (deref connection-closed 1000 false))
        (is (nil? @ssl-session) "SSL session should be undefined")))))

(deftest test-client-yields-stream-only-after-successful-handshake
  (let [connection-active (promise)
        continue-handshake (promise)
        notify-connection-active #_:clj-kondo/ignore (netty/channel-inbound-handler
                                                      :channel-active
                                                      ([_ ctx]
                                                       (deliver connection-active true)
                                                       (when-not (deref continue-handshake 5000 false)
                                                         (throw (TimeoutException.)))
                                                       (.fireChannelActive ctx)))]
    (with-server (tcp/start-server (fn [s _])
                                   {:port 10001
                                    :ssl-context ssl/server-ssl-context})
      (let [c (tcp/client {:host "localhost"
                           :port 10001
                           :ssl-context ssl/client-ssl-context-opts
                           :pipeline-transform (fn [p]
                                                 (.addAfter p "handler" "notify-active" notify-connection-active))})]
        (is (deref connection-active 1000 false))
        (is (not (d/realized? c)))
        (deliver continue-handshake true)
        (is (deref c 1000 false))))))
