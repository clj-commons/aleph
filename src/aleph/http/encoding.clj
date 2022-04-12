(ns aleph.http.encoding
  (:require
    [clj-commons.byte-streams :as bs]
    [clj-commons.primitive-math :as p])
  (:import
    [io.netty.buffer
     ByteBuf
     Unpooled]
    [io.netty.handler.codec.base64
     Base64]))

(set! *unchecked-math* true)

(defn qp-byte [^long b]
  (if (and (not= b 61) (or (= b 9) (<= 32 b 126)))
    (str (char b))
    (str "=" (.toUpperCase (Long/toHexString b)))))

(let [^objects encodings (->> (range 256) (map qp-byte) into-array)]
  (defn encode-qp [val]
    (let [sb (StringBuffer.)
          ^bytes ary (bs/to-byte-array val)
          len (alength ary)]
      (loop [i 0, newline 71]
        (when (p/< i len)
          (let [b (p/byte->ubyte (aget ary i))]
            (.append sb ^String (aget encodings b))
            (if (p/< newline (.length sb))
              (do
                (.append sb "=\n")
                (recur (p/inc i) (p/+ newline 76)))
              (recur (p/inc i) newline)))))
      (str sb))))

(defn encode-base64 [val]
  (let [cb (-> val bs/to-byte-buffer Unpooled/wrappedBuffer)
        encoded (Base64/encode cb)
        r (byte-array (.capacity ^ByteBuf encoded))
        _ (.getBytes ^ByteBuf encoded 0 r)]
    (.release ^ByteBuf cb)
    (.release ^ByteBuf encoded)
    (bs/to-byte-buffer r)))

(defn encode [val encoding]
  (case encoding
    :base64 (encode-base64 val)
    :quoted-printable (encode-qp val)
    :qp (encode-qp val)
    ;; "binary" effectively means "do nothing"
    :binary val
    (throw (IllegalArgumentException.
             (str "unsupported encodiing given:" (pr-str encoding))))))
