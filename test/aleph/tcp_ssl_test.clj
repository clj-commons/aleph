(ns aleph.tcp-ssl-test
  (:use
    [clojure test])
  (:require [aleph.tcp-test :refer [with-server]]
    [aleph.tcp :as tcp]
    [aleph.netty :as netty]
    [manifold.stream :as s]
    [byte-streams :as bs])
  (:import
   [java.security KeyFactory PrivateKey]
   [java.security.cert CertificateFactory X509Certificate]
   [java.io ByteArrayInputStream]
   [java.security.spec RSAPrivateCrtKeySpec]
   [io.netty.handler.ssl SslContextBuilder ClientAuth]
   [org.apache.commons.codec.binary Base64]))

(netty/leak-detector-level! :paranoid)

(set! *warn-on-reflection* false)

(defn gen-key
  ^PrivateKey [public-exponent k]
  (let [k (zipmap
            (keys k)
            (->> k
              vals
              (map #(BigInteger. % 16))))
        spec (RSAPrivateCrtKeySpec.
               (:modulus k)
               (biginteger public-exponent)
               (:privateExponent k)
               (:prime1 k)
               (:prime2 k)
               (:exponent1 k)
               (:exponent2 k)
               (:coefficient k))
        gen (KeyFactory/getInstance "RSA")]
    (.generatePrivate gen spec)))

(defn gen-cert
  ^X509Certificate [^String pemstr]
  (.generateCertificate (CertificateFactory/getInstance "X.509")
    (ByteArrayInputStream. (Base64/decodeBase64 pemstr))))

(def ca-cert (gen-cert (read-string (slurp "test/ca_cert.edn"))))

(def ca-key (gen-key 65537 (read-string (slurp "test/ca_key.edn"))))

(def server-cert (gen-cert (read-string (slurp "test/server_cert.edn"))))

(def server-key (gen-key 65537 (read-string (slurp "test/server_key.edn"))))

(def ^X509Certificate client-cert (gen-cert (read-string (slurp "test/client_cert.edn"))))

(def client-key (gen-key 65537 (read-string (slurp "test/client_key.edn"))))

(def server-ssl-context-opts
   {:private-key server-key
    :certificate-chain [server-cert]
    :trust-store [ca-cert]
    :client-auth :optional})

(def server-ssl-context
  (netty/ssl-server-context server-ssl-context-opts))

(def client-ssl-context-opts
    {:private-key client-key
     :certificate-chain [client-cert]
     :trust-store [ca-cert]})

(def client-ssl-context
  (netty/ssl-client-context client-ssl-context-opts))

(defn ssl-echo-handler
  [s c]
  (is (some? (:ssl-session c)) "SSL session should be defined")
  (s/connect
    ; note we need to inspect the SSL session *after* we start reading
    ; data. Otherwise, the session might not be set up yet.
    (s/map (fn [msg]
             (is (= (.getSubjectDN ^X509Certificate client-cert)
                   (.getSubjectDN ^X509Certificate (first (.getPeerCertificates (:ssl-session c))))))
             msg)
      s)
    s))

(deftest test-ssl-echo
  (with-server (tcp/start-server ssl-echo-handler
                                 {:port 10001
                                  :ssl-context server-ssl-context})
    (let [c @(tcp/client {:host "localhost"
                          :port 10001
                          :ssl-context client-ssl-context})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))

(deftest test-ssl-opts-echo
  (with-server (tcp/start-server ssl-echo-handler
                                 {:port 10001
                                  :ssl-context server-ssl-context-opts})
    (let [c @(tcp/client {:host "localhost"
                          :port 10001
                          :ssl-context client-ssl-context-opts})]
      (s/put! c "foo")
      (is (= "foo" (bs/to-string @(s/take! c)))))))
