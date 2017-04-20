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
  [{:keys [name content mime-type charset transfer-encoding filename]}]
  (let [mt (or mime-type
             (when (instance? File content)
               (URLConnection/guessContentTypeFromName (.getName ^File content))))]
    {:name name
     :content (bs/to-byte-buffer content)
     :mime-type (mime-type-descriptor mt charset)
     :transfer-encoding transfer-encoding
     :filename filename}))

;; Omit "content-transfer-encoding" when not provided
;;
;; RFC 2388, section 3:
;; Each part may be encoded and the "content-transfer-encoding" header
;; supplied if the value of that part does not conform to the default
;; encoding.
;;
;; Include local filename when provided. It might be required by a server
;; when dealing with users' file uploads.
;;
;; RFC 2388, section 4.4:
;; The original local file name may be supplied as well...
(defn part-headers [^String name ^String mime-type transfer-encoding filename]
  (let [te (when transfer-encoding (cc/name transfer-encoding))
        cd (str "content-disposition: form-data; name=\"" (encode name :qp) "\""
                (when filename (str "; filename=\"" (encode filename :qp) "\""))
                \newline)
        ct (str "content-type: " mime-type \newline)
        cte (if-not te "" (str "content-transfer-encoding: " te \newline \newline))
        lcd (.length cd)
        lct (.length ct)
        lcte (.length cte)
        size (+ lcd lct lcte)
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put (bs/to-byte-buffer cd))
      (.put (bs/to-byte-buffer ct))
      (.put (bs/to-byte-buffer cte))
      (.flip))))

(defn encode-part
  "Generates the byte representation of a part for the bytebuffer"
  [{:keys [name content mime-type charset transfer-encoding filename] :as part}]
  (let [headers (part-headers name mime-type transfer-encoding filename)
        body (bs/to-byte-buffer (encode content (or transfer-encoding :qp)))
        header-len (.limit ^ByteBuffer headers)
        size (+ header-len (.limit ^ByteBuffer body))
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put ^ByteBuffer headers)
      (.put ^ByteBuffer body))))

(def ^ByteBuffer newline-bytes-buffer (bs/to-byte-buffer "\n"))
(def ^ByteBuffer dashes-bytes-buffer (bs/to-byte-buffer "--"))

(defn encode-body
  ([parts]
   (encode-body (boundary) parts))
  ([^String boundary parts]
   (let [b (bs/to-byte-buffer (str "--" boundary))
         b-len (+ 5 (.length boundary))
         ps (map #(-> % populate-part encode-part) parts)
         boundaries-len (* (inc (count parts)) b-len)
         part-len (reduce (fn [acc ^ByteBuffer p] (+ acc (.limit p))) 0 ps)
         buf (ByteBuffer/allocate (+ 2 boundaries-len part-len))]
     (.put buf b)
     (doseq [^ByteBuffer part ps]
       (.put buf newline-bytes-buffer)
       (.flip part)
       (.put buf part)
       (.put buf newline-bytes-buffer)
       (.put buf newline-bytes-buffer)
       (.flip b)
       (.put buf b))
     (.put buf dashes-bytes-buffer)
     (.flip buf)
     (bs/to-byte-array buf))))
