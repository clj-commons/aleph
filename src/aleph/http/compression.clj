(ns ^:no-doc aleph.http.compression
  "Currently only for HTTP/2, since Netty offers better support for
   compression in HTTP/1 code.

   Best supported compression codecs on the web are Brotli, gzip, and deflate.

   Snappy is primarily an internal Google codec, but is supported by some
   open-source databases. It's not on track to be a web standard, but is
   well-supported by Netty.

   Zstd is a Facebook codec that is registered with IANA, but is not yet
   widely available. (See https://caniuse.com/zstd).

   See https://www.iana.org/assignments/http-parameters/http-parameters.xml#content-coding"
  (:require
    [aleph.netty :as netty]
    [clj-commons.primitive-math :as p]
    [clojure.tools.logging :as log])
  (:import
    (aleph.http AlephCompressionOptions)
    (io.netty.channel ChannelHandler)
    (io.netty.handler.codec.compression
      Brotli
      BrotliOptions CompressionOptions
      DeflateOptions
      GzipOptions
      SnappyOptions
      Zstd ZstdOptions)
    (io.netty.handler.codec.http HttpHeaderNames)
    (io.netty.handler.codec.http2 Http2HeadersFrame)
    (io.netty.util AsciiString)
    (java.util.concurrent.atomic AtomicBoolean)))

;; AsciiStrings are efficient but annoying, since Netty forces you to consider them
(def ^:private ^AsciiString identity-encoding (AsciiString. "identity"))
(def ^:private ^AsciiString head-method (AsciiString. "HEAD"))
(def ^:private ^AsciiString connect-method (AsciiString. "CONNECT"))

(defn- contains-class?
  "Returns true if the class is in the array"
  [^"[Lio.netty.handler.codec.compression.CompressionOptions;" a ^Class klazz]
  (let [len (alength a)]
    (loop [i 0]
      (if (>= i len)
        false
        (if (.equals klazz (class (aget ^"[Lio.netty.handler.codec.compression.CompressionOptions;" a i)))
          true
          (recur (unchecked-inc-int i)))))))

(def ^"[Lio.netty.handler.codec.compression.CompressionOptions;"
  available-compressor-options
  "A Java array of all available compressor options"
  (into-array CompressionOptions
              (cond-> [(AlephCompressionOptions/deflate)
                       (AlephCompressionOptions/gzip)
                       (AlephCompressionOptions/snappy)]
                      (Brotli/isAvailable) (conj (AlephCompressionOptions/brotli))
                      (Zstd/isAvailable) (conj (AlephCompressionOptions/zstd)))))


(defn- qvalue
  "Parses qvalue from an accept-encoding header. Defaults to 1.0 if not
   specified. On error, defaults to 0, effectively disabling that encoding."
  [^String enc]
  (let [equals-idx (.indexOf enc "=")]
    (if (p/not== equals-idx -1)
      (try
        (Double/parseDouble (.substring enc (inc equals-idx)))
        (catch NumberFormatException e
          0.0))
      1.0)))

(defrecord Qvals [^double star ^double br ^double snappy ^double zstd ^double gzip ^double deflate])

(defn choose-codec
  "Based on Netty's algorithm, which only compares with the next-best option.
   E.g., Brotli's q-value is only compared with zstd, not gzip or deflate.

   Preferred order is: br, zstd, snappy, gzip, deflate"
  ;; TODO: switch to field access over key destructuring??
  [^"[Lio.netty.handler.codec.compression.CompressionOptions;" compressor-options
   {:keys [^double star ^double br ^double zstd ^double snappy ^double gzip ^double deflate] :as qvs}]
  (cond (or (p/> br 0.0)
            (p/> zstd 0.0)
            (p/> snappy 0.0)
            (p/> gzip 0.0)
            (p/> deflate 0.0))
        ;; some encodings were listed
        (cond (and (p/not== br -1.0)
                   (p/>= br zstd)
                   (contains-class? compressor-options BrotliOptions))
              "br"

              (and (p/not== zstd -1.0)
                   (p/>= zstd snappy)
                   (contains-class? compressor-options ZstdOptions))
              "zstd"

              (and (p/not== snappy -1.0)
                   (p/>= snappy gzip)
                   (contains-class? compressor-options SnappyOptions))
              "snappy"

              (and (p/not== gzip -1.0)
                   (p/>= gzip deflate)
                   (contains-class? compressor-options GzipOptions))
              "gzip"

              (and (p/not== deflate -1.0)
                   (contains-class? compressor-options DeflateOptions))
              "deflate")

        ;; no named encodings were listed, so we'll apply *'s qval to unset ones
        (p/> star 0.0)
        (cond (and (p/== br -1.0)
                   (Brotli/isAvailable))
              "br"

              (and (p/== zstd -1.0)
                   (Zstd/isAvailable))
              "zstd"

              (p/== snappy -1.0)
              "snappy"

              (p/== gzip -1.0)
              "gzip"

              (p/== deflate -1.0)
              "deflate"

              :else nil)

        :else nil))

(defn parse-accept-encoding
  "Adapted from HttpContentCompressor.determineEncoding()"
  [^CharSequence accept-encoding]
  (let [encs (.split (.toString accept-encoding) ",")]
    (areduce encs i qvs (->Qvals -1.0 -1.0 -1.0 -1.0 -1.0 -1.0)
             (let [enc ^String (aget encs i)
                   qval (qvalue enc)]
               (cond
                 (.contains enc "*") (assoc qvs :star qval)
                 (.contains enc "br") (assoc qvs :br qval)
                 (.contains enc "snappy") (assoc qvs :snappy qval)
                 (.contains enc "zstd") (assoc qvs :zstd qval)
                 (.contains enc "gzip") (assoc qvs :gzip qval)
                 (.contains enc "deflate") (assoc qvs :deflate qval)
                 :else qvs)))))


(defn h2-compression-handler
  "Creates a new compression handler.

   Parses the accept-encoding header, and selects a suitable content-encoding
   to use, if any. Ignores HEAD and CONNECT requests, since they don't have
   bodies. Ignores an empty accept-encoding header, as well as a value of
   \"identity\".

   Will not compress body-less 1xx, 204, or 304 status responses.

   This handler only sets the content-encoding header. It must be used in
   conjunction with AlephHttp2FrameCodecBuilder to attach the actual
   compression decorator.

   See https://www.rfc-editor.org/rfc/rfc9110#content.codings for more."
  (^ChannelHandler []
   (h2-compression-handler available-compressor-options))
  (^ChannelHandler
   [^"[Lio.netty.handler.codec.compression.CompressionOptions;" compressor-options]
   (let [seen-headers? (AtomicBoolean. false)               ; 9x faster than atom
         encoding (atom identity-encoding)]

     (netty/channel-handler
       :channel-read
       ([_ ctx msg]
        (when (and (instance? Http2HeadersFrame msg)
                   (not (.get seen-headers?)))
          ;; for ignoring trailers
          (.set seen-headers? true)

          (let [headers (.headers ^Http2HeadersFrame msg)]
            (when-let [^CharSequence accept-encoding
                       (.get headers HttpHeaderNames/ACCEPT_ENCODING)]
              ;; CharSequence.isEmpty() not available before 15
              (when (and (not (p/zero? (.length accept-encoding)))
                         (not (.contentEquals identity-encoding accept-encoding)))
                (let [method (.method headers)]
                  (when (and (not (.contentEquals head-method method))
                             (not (.contentEquals connect-method method)))

                    ;; TODO: memoize or cache the below selection code, should be 2x faster
                    (some->> (parse-accept-encoding accept-encoding)
                             (choose-codec compressor-options)
                             (reset! encoding))
                    (log/debug "Chose content-encoding:" @encoding "based on" accept-encoding)))))))
        (.fireChannelRead ctx msg))

       :write
       ([_ ctx msg promise]
        (when (instance? Http2HeadersFrame msg)
          (let [^String chosen-encoding @encoding]
            (when (and (some? chosen-encoding)
                       (not (.contentEquals identity-encoding chosen-encoding))
                       ;; CharSequence.isEmpty() not available before 15
                       (not (p/zero? ^int (.length chosen-encoding))))
              (let [headers (.headers ^Http2HeadersFrame msg)
                    status (-> headers (.status) (AsciiString/of) (.parseInt 10))]
                (when (not (or (p/<= status 199)
                               (p/== 204 status)
                               (p/== 304 status)))
                  (log/debug "Setting content-encoding to:" @encoding)
                  (.set headers HttpHeaderNames/CONTENT_ENCODING chosen-encoding))))))

        (.write ctx msg promise))))))
