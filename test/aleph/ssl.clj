(ns aleph.ssl
  (:require
   [aleph.netty :as netty])
  (:import
   (io.netty.handler.ssl ClientAuth SslContextBuilder)
   (java.io ByteArrayInputStream)
   (java.security KeyFactory PrivateKey)
   (java.security.cert CertificateFactory X509Certificate)
   (java.security.spec RSAPrivateCrtKeySpec)
   (org.apache.commons.codec.binary Base64)))

(set! *warn-on-reflection* false)

(defn gen-cert
  ^X509Certificate [^String pemstr]
  (.generateCertificate (CertificateFactory/getInstance "X.509")
                        (ByteArrayInputStream. (Base64/decodeBase64 pemstr))))

(defn gen-key
  ^PrivateKey [public-exponent k]
  (let [k (zipmap
           (keys k)
           (->> k
                vals
                (map #(BigInteger. ^String % 16))))
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

(def server-cert (gen-cert (read-string (slurp "test/server_cert.edn"))))
(def server-key (gen-key 65537 (read-string (slurp "test/server_key.edn"))))

(def ca-cert (gen-cert (read-string (slurp "test/ca_cert.edn"))))
(def ca-key (gen-key 65537 (read-string (slurp "test/ca_key.edn"))))

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
