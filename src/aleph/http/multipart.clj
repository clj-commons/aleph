(ns aleph.http.multipart
  (:require
   [clojure.core :as cc]
   [byte-streams :as bs]
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
   [io.netty.util ReferenceCounted]
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

(defn
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
  boundary []
  (-> (ThreadLocalRandom/current) .nextLong Long/toHexString .toLowerCase))

(defn mime-type-descriptor
  [^String mime-type ^String encoding]
  (str
   (-> (or mime-type "application/octet-stream") .trim (.toLowerCase Locale/US))
   (when encoding
     (str "; charset=" encoding))))

(defn
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
  populate-part
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
(defn
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
  part-headers [^String part-name ^String mime-type transfer-encoding name]
  (let [cd (str "Content-Disposition: form-data; name=\"" part-name "\""
             (when name (str "; filename=\"" name "\""))
             "\r\n")
        ct (str "Content-Type: " mime-type "\r\n")
        cte (if (nil? transfer-encoding)
              ""
              (str "Content-Transfer-Encoding: " (cc/name transfer-encoding) "\r\n"))]
    (bs/to-byte-buffer (str cd ct cte "\r\n"))))

(defn
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
  encode-part
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
  ^{:deprecated "use aleph.http.multipart/encode-request instead"}
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
                              (or name "")
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

(defrecord MultipartChunk [part-name
                           content
                           name
                           charset
                           mime-type
                           transfer-encoding
                           memory?
                           file?
                           file
                           size
                           ^ReferenceCounted raw-http-data]
  ReferenceCounted
  (refCnt [_]
    (.refCnt raw-http-data))
  (retain [_]
    (.retain raw-http-data))
  (retain [_ increment]
    (.retain raw-http-data increment))
  (^ReferenceCounted touch [_]
    (.touch raw-http-data))
  (^ReferenceCounted touch [_ ^Object hint]
    (.touch raw-http-data hint))
  (release [_]
    (.release raw-http-data))
  (release [_ decrement]
    (.release raw-http-data decrement)))

(defmulti http-data->map
  (fn [^InterfaceHttpData data]
    (.getHttpDataType data)))

(defmethod http-data->map InterfaceHttpData$HttpDataType/Attribute
  [^Attribute attr]
  (let [content (.getValue attr)]
    (map->MultipartChunk
     {:part-name (.getName attr)
      :content content
      :name nil
      :charset (-> attr .getCharset .toString)
      :mime-type nil
      :transfer-encoding nil
      :memory? (.isInMemory attr)
      :file? false
      :file nil
      :size (count content)
      :raw-http-data attr})))

(defmethod http-data->map InterfaceHttpData$HttpDataType/FileUpload
  [^FileUpload data]
  (let [memory? (.isInMemory data)]
    (map->MultipartChunk
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
      :size (.length data)
      :raw-http-data data})))

(defn- read-attributes [^HttpPostRequestDecoder decoder parts]
  (d/loop []
    (if-not (.hasNext decoder)
      (d/success-deferred true) ;; go for another chunk of body
      (let [^InterfaceHttpData data (.next decoder)]
        (if (nil? data)
          ;; this probably could happen only in case of
          ;; simultaneous access to the decoder object...
          (d/success-deferred true)
          (d/chain'
           (s/put! parts (http-data->map data))
           (fn [succeed?]
             (if succeed?
               (do
                 (.removeHttpDataFromClean decoder data)
                 (d/recur))
               (d/success-deferred false)))))))))

(defn decode-request
  "Takes a ring request and returns a manifold stream which yields
   parts of the mutlipart/form-data encoded body. In case the size of
   a part content exceeds `:memory-limit` limit (16KB by default),
   corresponding payload would be written to a temp file. Check `:memory?`
   flag to know whether content might be read directly from `:content` or
   should be fetched from the file specified in `:file`.

   Each part should be released using `netty/release` helper to cleanup
   allocated buffers and temp files (if any) as soon as the data is fully
   consumed (i.e. temp file moved to a target location). Note, it's also
   safer to close the stream of chunks manually to ensure all chunks that
   were never read from the stream but were already consumed from the connection,
   are also deallocated.

   Typical usage looks like:

   ```
   (require '[aleph.http.multipart :as multipart])
   (require '[aleph.netty :as netty])
   (require '[manifold.stream :as stream])
   (require '[clojure.java.io :as io])

   (defn file-upload-handler [req]
     (let [chunks (multipart/decode-request req)]
       (d/chain'
         (stream/take! chunks)
         (fn [{:keys [file] :as chunk}]
           (io/copy file writer)
           (netty/release chunk)
           (stream/close! chunks)
           {:status 200 :body \"Succesfull!\"}))))
   ```

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
          ;; note, that HttpPostRequestDecoder actually
          ;; makes a copy of the content provided, so we can
          ;; release it here
          ;; https://github.com/netty/netty/blob/d05666ae2d2068da7ee031a8bfc1ca572dbcc3f8/codec-http/src/main/java/io/netty/handler/codec/http/multipart/HttpPostMultipartRequestDecoder.java#L329
          (netty/release chunk)
          (read-attributes decoder parts)))
      parts)

     (s/on-closed
      parts
      (fn []
        (when (compare-and-set! destroyed? false true)
          (try
            ;; we're removing each received http data chunk
            ;; from cleanup queue before pushing to `parts` stream,
            ;; meaning that this `destroy` call would only cleanup
            ;; those chunck that were not consumed for some reasons
            ;; (i.e. the user closes the stream given earlier)
            (.destroy decoder)
            (catch Exception e
              (log/warn e "exception when cleaning up multipart decoder"))))))

     parts)))
