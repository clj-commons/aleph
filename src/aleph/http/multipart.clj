(ns aleph.http.multipart
  (:require
    [clojure.core :as cc]
    [byte-streams :as bs]
    [aleph.http.encoding :refer [encode]])
  (:import
    [java.util
     Locale]
    [java.io
     File]
    [java.nio
     ByteBuffer]
    [java.nio.charset
     Charset]
    [java.net
     URLConnection]
    [io.netty.util.internal
     ThreadLocalRandom]))

(defn boundary []
  (-> (ThreadLocalRandom/current) .nextLong Long/toHexString .toLowerCase))

(defn mime-type-descriptor
  [^String mime-type ^String encoding]
  (str
    (-> (or mime-type "application/octet-stream") .trim (.toLowerCase Locale/US))
    (when encoding
      (str ";charset=" encoding))))

(defn populate-part
  "Generates a part map of the appropriate format"
  [{:keys [name content mime-type charset transfer-encoding] :or {:transfer-encoding :quoted-printable}}]
  (let [mt (or mime-type
             (when (instance? File content)
               (URLConnection/guessContentTypeFromName (.getName ^File content))))]
    {:name name :content (bs/to-byte-buffer content)
     :mime-type (mime-type-descriptor mt charset)
     :transfer-encoding transfer-encoding}))

(defn part-headers [name mime-type transfer-encoding]
  (let [te (cc/name transfer-encoding)
        cd (str "content-disposition: form-data; name=\"" (encode name :qp) \newline)
        ct (str "content-type: " mime-type \newline)
        cte (str "content-transfer-encoding: " te "\n\n")
        lcd (.length cd)
        lct (.length ct)
        lcte (.length cte)
        size (+ lcd lct lcte)
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put 0 (bs/to-byte-buffer cd))
      (.put lcd (bs/to-byte-buffer ct))
      (.put (+ lcd lct) (bs/to-byte-buffer cte)))))

(defn encode-part
  "Generates the byte representation of a part for the bytebuffer"
  [{:keys [name content mime-type charset transfer-encoding] :as part}]
  ;; encode name, content`
  (let [headers (part-headers name mime-type transfer-encoding)
        body (encode content transfer-encoding)
        header-len (.length ^String headers)
        size (+ header-len (.length ^String body))
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put 0 headers)
      (.put header-len body))))

(defn encode-body
  ([parts]
    (encode-body (boundary) parts))
  ([^String boundary parts]
    (let [b (bs/to-byte-buffer boundary)
          b-len (+ 2 (.length boundary))
          ps (map #(-> % populate-part encode-part) parts)
          boundaries-len (* (inc (count parts)) b-len)
          part-len (reduce (fn [acc ^String p] (+ acc (.length p))) 0 ps)
          buf (ByteBuffer/allocate (+ boundaries-len part-len))]
      (.put buf 0 b)
      (reduce (fn [idx part]
                (let [p-len (.length ^String part)]
                  (.put buf idx part)
                  (.put buf (+ idx part-len) b)
                  (+ idx part-len b-len))) b-len ps)
      buf)))
