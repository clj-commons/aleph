(ns aleph.http.multipart
  (:require
   [clojure.core :as cc]
   [clj-commons.byte-streams :as bs]
   [aleph.http.encoding :refer [encode]]
   [aleph.http.core :as http-core]
   [aleph.netty :as netty]
   [manifold.stream :as s]
   [clojure.tools.logging :as log]
   [manifold.deferred :as d])
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
    ThreadLocalRandom]
   [io.netty.handler.codec.http
    DefaultHttpContent
    DefaultHttpRequest
    FullHttpRequest
    HttpConstants]
   [io.netty.handler.codec.http.multipart
    Attribute
    MemoryAttribute
    FileUpload
    HttpDataFactory
    DefaultHttpDataFactory
    HttpPostRequestDecoder
    HttpPostRequestEncoder
    InterfaceHttpData
    InterfaceHttpData$HttpDataType]))

(defn boundary []
  (-> (ThreadLocalRandom/current) .nextLong Long/toHexString .toLowerCase))

(defn mime-type-descriptor
  [^String mime-type ^String encoding]
  (str
   (-> (or mime-type "application/octet-stream") .trim (.toLowerCase Locale/US))
   (when encoding
     (str "; charset=" encoding))))

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

(defn
  ^{:deprecated "0.4.7-alpha2"
    :superseded-by "encode-request"}
  encode-body
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

(defn encode-request [^DefaultHttpRequest req parts]
  (let [^HttpPostRequestEncoder encoder (HttpPostRequestEncoder. req true)]
    (doseq [{:keys [part-name content mime-type charset name]} parts]
      (if (instance? File content)
        (let [filename (.getName ^File content)
              name (or name filename)
              mime-type (or mime-type
                            (URLConnection/guessContentTypeFromName filename))
              content-type (mime-type-descriptor mime-type charset)]
          (.addBodyFileUpload encoder
                              (or part-name name)
                              ;; Netty's multipart encoder ignores empty strings here
                              (or filename "")
                              content
                              content-type
                              false))
        (let [^Charset charset (cond
                                 (nil? charset)
                                 HttpConstants/DEFAULT_CHARSET

                                 (string? charset)
                                 (Charset/forName charset)

                                 (instance? Charset charset)
                                 charset)
              part-name (or part-name name)
              attr (if (string? content)
                     (MemoryAttribute. ^String part-name ^String content charset)
                     (doto (MemoryAttribute. ^String part-name charset)
                       (.addContent (netty/to-byte-buf content) true)))]
          (.addBodyHttpData encoder attr))))
    (let [req' (.finalizeRequest encoder)]
      [req' (when (.isChunked encoder) encoder)])))

(defmulti http-data->map
  (fn [^InterfaceHttpData data]
    (.getHttpDataType data)))

(defmethod http-data->map InterfaceHttpData$HttpDataType/Attribute
  [^Attribute attr]
  (let [content (.getValue attr)]
    {:part-name (.getName attr)
     :content content
     :name nil
     :charset (-> attr .getCharset .toString)
     :mime-type nil
     :transfer-encoding nil
     :memory? (.isInMemory attr)
     :file? false
     :file nil
     :size (count content)}))

(defmethod http-data->map InterfaceHttpData$HttpDataType/FileUpload
  [^FileUpload data]
  (let [memory? (.isInMemory data)]
    {:part-name (.getName data)
     :content (when memory?
                (bs/to-input-stream (netty/acquire (.content data))))
     :name (.getFilename data)
     :charset (-> data .getCharset .toString)
     :mime-type (.getContentType data)
     :transfer-encoding (.getContentTransferEncoding data)
     :memory? memory?
     :file? true
     :file (when-not memory? (.getFile data))
     :size (.length data)}))

(defn- read-attributes [^HttpPostRequestDecoder decoder parts]
  (while (.hasNext decoder)
    (s/put! parts (http-data->map (.next decoder)))))

(defn decode-request
  "Takes a ring request and returns a manifold stream which yields
   parts of the mutlipart/form-data encoded body. In case the size of
   a part content exceeds `:memory-limit` limit (16KB by default),
   corresponding payload would be written to a temp file. Check `:memory?`
   flag to know whether content might be read directly from `:content` or
   should be fetched from the file specified in `:file`.

   Note, that if your handler works with multipart requests only,
   it's better to set `:raw-stream?` to `true` to avoid additional
   input stream coercion."
  ([req] (decode-request req {}))
  ([{:keys [body] :as req}
    {:keys [body-buffer-size
            memory-limit]
     :or {body-buffer-size 65536
          memory-limit DefaultHttpDataFactory/MINSIZE}}]
   (let [body (if (s/stream? body)
                body
                (netty/to-byte-buf-stream body body-buffer-size))
         destroyed? (atom false)
         req' (http-core/ring-request->netty-request req)
         factory (DefaultHttpDataFactory. (long memory-limit))
         decoder (HttpPostRequestDecoder. factory req')
         parts (s/stream)]

     ;; on each HttpContent chunk, put it into the decoder
     ;; and resume our attempts to get the next attribute available
     (s/connect-via
      body
      (fn [chunk]
        (let [content (DefaultHttpContent. chunk)]
          (.offer decoder content)
          (read-attributes decoder parts)
          ;; note, that releasing chunk right here relies on
          ;; the internals of the decoder. in case those
          ;; internal are changed in future, this flow of
          ;; manipulations should be also reconsidered
          (netty/release chunk)
          (d/success-deferred true)))
      parts)

     (s/on-closed
      parts
      (fn []
        (when (compare-and-set! destroyed? false true)
          (try
            (.destroy decoder)
            (catch Exception e
              (log/warn e "exception when cleaning up multipart decoder"))))))

     parts)))
