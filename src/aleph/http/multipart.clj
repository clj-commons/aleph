(ns aleph.http.multipart
  (:require
   [clojure.core :as cc]
   [byte-streams :as bs]
   [aleph.http.encoding :refer [encode]]
   [aleph.http.core :as http-core]
   [manifold.stream :as s])
  (:import
   [java.util
    Locale]
   [java.io
    File]
   [java.nio
    ByteBuffer]
   [java.net
    URLConnection]
   [io.netty.util.internal
    ThreadLocalRandom]
   [io.netty.handler.codec.http
    DefaultHttpContent]
   [io.netty.handler.codec.http.multipart
    Attribute
    FileUpload
    HttpPostRequestDecoder
    InterfaceHttpData
    InterfaceHttpData$HttpDataType]))

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
  [{:keys [part-name content mime-type charset transfer-encoding name]}]
  (let [file? (instance? File content)
        mt (or mime-type
             (when file?
               (URLConnection/guessContentTypeFromName (.getName ^File content))))
        ;; populate file name when working with file object
        filename (or name (when file? (.getName ^File content)))
        ;; use "name" as a part name when the last is not provided
        part-name-to-use (or part-name name filename)]
    {:part-name part-name-to-use
     :content (bs/to-byte-buffer content)
     :mime-type (mime-type-descriptor mt charset)
     :transfer-encoding transfer-encoding
     :name filename}))

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
;;
;; Note, that you can use transfer-encoding=nil or :binary to leave data "as is".
;; transfer-encoding=nil omits "Content-Transfer-Encoding" header.
(defn part-headers [^String part-name ^String mime-type transfer-encoding name]
  (let [cd (str "Content-Disposition: form-data; name=\"" part-name "\""
             (when name (str "; filename=\"" name "\""))
             "\r\n")
        ct (str "Content-Type: " mime-type "\r\n")
        cte (if (nil? transfer-encoding)
              ""
              (str "Content-Transfer-Encoding: " (cc/name transfer-encoding) "\r\n"))]
    (bs/to-byte-buffer (str cd ct cte "\r\n"))))

(defn encode-part
  "Generates the byte representation of a part for the bytebuffer"
  [{:keys [part-name content mime-type charset transfer-encoding name] :as part}]
  (let [headers (part-headers part-name mime-type transfer-encoding name)
        body (bs/to-byte-buffer (if (some? transfer-encoding)
                                  (encode content transfer-encoding)
                                  content))
        header-len (.limit ^ByteBuffer headers)
        size (+ header-len (.limit ^ByteBuffer body))
        buf (ByteBuffer/allocate size)]
    (doto buf
      (.put ^ByteBuffer headers)
      (.put ^ByteBuffer body)
      (.flip))))

(defn encode-body
  ([parts]
    (encode-body (boundary) parts))
  ([^String boundary parts]
    (let [b (bs/to-byte-buffer (str "--" boundary))
          b-len (+ 6 (.length boundary))
          ps (map #(-> % populate-part encode-part) parts)
          boundaries-len (* (inc (count parts)) b-len)
          part-len (reduce (fn [acc ^ByteBuffer p] (+ acc (.limit p))) 0 ps)
          buf (ByteBuffer/allocate (+ 2 boundaries-len part-len))]
      (.put buf b)
      (doseq [^ByteBuffer part ps]
        (.put buf (bs/to-byte-buffer "\r\n"))
        (.put buf part)
        (.put buf (bs/to-byte-buffer "\r\n"))
        (.flip b)
        (.put buf b))
      (.put buf (bs/to-byte-buffer "--"))
      (.flip buf)
      (bs/to-byte-array buf))))

(defmulti http-data->map
  (fn [^InterfaceHttpData data]
    (.getHttpDataType data)))

(defmethod http-data->map InterfaceHttpData$HttpDataType/Attribute
  [^Attribute attr]
  (let [content (.getValue attr)]
    {:part-name (.getName attr)
     :content content
     :name nil
     :charset (.getCharset attr)
     :mime-type nil
     :transfer-encoding nil
     :memory? (.isInMemory attr)
     :file? false
     :size (count content)}))

(defmethod http-data->map InterfaceHttpData$HttpDataType/FileUpload
  [^FileUpload data]
  {:part-name (.getName data)
   :content (.getValue data)
   :name (.getFilename data)
   :charset (.getCharset data)
   :mime-type (.getContentType data)
   :transfer-encoding (.getContentTransferEncoding data)
   :memory? (.isInMemory data)
   :file? true
   :file (when-not (.isInMemory data) (.getFile data))
   :size (.length data)})

;; xxx: cleanup everything after chunks is read
(defn- read-attributes [^HttpPostRequestDecoder decoder]
  (let [chunks (s/stream)]
    (while (.hasNext decoder)
      (s/put! chunks (http-data->map (.next decoder))))
    (s/close! chunks)
    chunks))

;; xxx: helper to move disk file to another location
;; xxx: read from InputStream as well
(defn decode-raw-stream-request
  "Takes request from :raw-stream?=true handler and returns
   a manifold stream which yields parts of the mutlipart/form-data
   encoded body."
  [{:keys [body] :as req}]
  (when-not (s/stream? body)
    (throw (IllegalArgumentException.
            "Request body should be a stream of ByteBuf's")))
  (let [chunks (s/stream->seq body)
        req' (http-core/ring-request->netty-request req)
        ^HttpPostRequestDecoder decoder (HttpPostRequestDecoder. req')]
    ;; xxx: chunks might be added afterwards,
    ;; no need to read an entire stream before decoding
    (doseq [c chunks
            ;; xxx: decide when to cleanup Netty buffers :expressionless:
            :let [content (DefaultHttpContent. c)]]
      (.offer decoder content))
    (read-attributes decoder)))
