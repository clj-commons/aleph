(ns aleph.http.encoding
  (:require
    [byte-streams :as bs])
  (:import
    [java.nio
     Charset]
    [io.netty.handler.codec.base64
     Base64]
    [org.apache.commons.codec.net
     QuotedPrintableCodec])
  (:require-clojure :as cc))

(defn- encode-qp [val {:keys [charset] :or {:charset "utf-8"}}] ;; us-ascii ?
  (-> charset Charset/forName QuotedPrintableCodec. (.encode val)))

(defn- encode-b64 [val]
  (-> val bs/to-byte-buffer Base64/decode))

(def encoders
  {:base64 encode-b64
   :quoted-printable encode-qp
   :qp encode-qp})

(defn encode [val encoding]
  (when-let [r (encoders encoding)]
    (r val)))
