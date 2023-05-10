(ns ^:no-doc aleph.http.http2
  (:require
    [aleph.http.file :as file]
    [aleph.netty :as netty]
    [clj-commons.primitive-math :as p]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.http.file HttpFile)
    (io.netty.buffer ByteBuf)
    (io.netty.channel FileRegion)
    (io.netty.handler.codec.http2
      DefaultHttp2DataFrame
      DefaultHttp2Headers
      DefaultHttp2HeadersFrame
      Http2DataChunkedInput
      Http2Error
      Http2FrameStream
      Http2FrameStreamException
      Http2Headers
      Http2StreamChannel)
    (io.netty.handler.ssl SslHandler)
    (io.netty.handler.stream
      ChunkedFile
      ChunkedInput
      ChunkedNioFile
      ChunkedWriteHandler)
    (io.netty.util.internal StringUtil)
    (java.io
      File
      RandomAccessFile)
    (java.nio ByteBuffer)
    (java.nio.channels FileChannel)
    (java.nio.file
      OpenOption
      Path
      StandardOpenOption)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (Class/forName "[B"))

;; See https://httpwg.org/specs/rfc9113.html#ConnectionSpecific
(def invalid-headers #{"connection" "proxy-connection" "keep-alive" "upgrade"})

(defn- add-header
  "Add a single header and value. The value can be a string or a collection of
   strings.

   Respects HTTP/2 rules. Strips invalid connection-related headers. Throws on
   nil header values. Throws if `transfer-encoding` is present, but not 'trailers'."
  [^Http2Headers h2-headers ^String header-name header-value]
  (println "adding header" header-name header-value)
  (if (nil? header-name)
    (throw (IllegalArgumentException. "Header name cannot be nil"))
    (let [header-name (str/lower-case header-name)]         ; http2 requires lowercase headers
      (cond
        (nil? header-value)
        (throw (IllegalArgumentException. (str "Invalid nil value for header '" header-name "'")))

        (invalid-headers header-name)
        (do
          (println (str "Forbidden HTTP/2 header: \"" header-name "\""))
          (throw
            (IllegalArgumentException. (str "Forbidden HTTP/2 header: \"" header-name "\""))))

        ;; See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Transfer-Encoding
        (and (.equals "transfer-encoding" header-name)
             (not (.equals "trailers" header-value)))
        (throw
          (IllegalArgumentException. "Invalid value for 'transfer-encoding' header. Only 'trailers' is allowed."))

        (sequential? header-value)
        (.add h2-headers ^CharSequence header-name ^Iterable header-value)

        :else
        (.add h2-headers ^CharSequence header-name ^Object header-value)))))

(defn- ring-map->netty-http2-headers
  "Builds a Netty Http2Headers object from a Ring map."
  ^DefaultHttp2Headers
  [req]
  (prn req)
  (let [headers (get req :headers)
        h2-headers (doto (DefaultHttp2Headers.)
                         (.method (-> req (get :request-method) name str/upper-case))
                         (.scheme (-> req (get :scheme) name))
                         (.authority (:authority req))
                         (.path (str (get req :uri)
                                     (when-let [q (get req :query-string)]
                                       (str "?" q)))))]
    (when (:status req)
      (.status h2-headers (-> req (get :status) str)))

    (when headers
      (run! #(add-header h2-headers (key %) (val %))
            headers))
    h2-headers))

(defn- try-set-content-length!
  "Attempts to set the `content-length` header if not already set.

   Will skip if it's a response and the status code is 1xx or 204.
   Negative length values are ignored."
  ^Http2Headers
  [^Http2Headers headers ^long length]
  (when-not (.get headers "content-length")
    (if (some? (.status headers))
      (let [code (-> headers (.status) (Long/parseLong))]
        (when-not (or (<= 100 code 199)
                      (== 204 code))
          (.setLong headers "content-length" length)))
      (.setLong headers "content-length" length))))


(defn send-contiguous-body
  "Write out a msg and a body that is, or can be turned into a ByteBuf.

   Works with strings, byte arrays, ByteBuffers, ByteBufs, nil, and
   anything supported by byte-streams' `convert`"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body]
  (let [body-bb (netty/to-byte-buf ch body)]
    (try-set-content-length! headers (.readableBytes body-bb))

    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch (-> body-bb (DefaultHttp2DataFrame. true) (.stream stream)))))

(defn- ensure-chunked-writer
  "Adds the ChunkedWriteHandler to the pipeline if needed"
  [^Http2StreamChannel ch]
  (let [p (.pipeline ch)]
    (when-not (.get p ChunkedWriteHandler)
        (.addBefore p
                    "handler"
                    "streamer"
                    (ChunkedWriteHandler.)))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers ^ChunkedInput body]
  (log/trace "Sending ChunkedInput")
  (let [len (.length body)]
    (when (p/>= len 0)
      (try-set-content-length! headers len)))

  (ensure-chunked-writer ch)

  (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
  (netty/write-and-flush ch (-> body (Http2DataChunkedInput. stream))))

(defn send-file-region
  "Write out a msg and a FileRegion body.

   WARNING: Netty does not support this with the SslHandler. While there are
   some OSes/NICs that can support SSL and zero-copy, Netty does not."
  [^Http2StreamChannel ch ^Http2Headers headers ^FileRegion fr]
  (let [stream (.stream ch)]
    (try-set-content-length! headers (.count fr))
    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch fr)))

(defn send-http-file
  "Send an HttpFile using ChunkedNioFile"
  [ch stream headers ^HttpFile hf]
  (let [file-channel (-> hf
                         ^File (.-fd)
                         (.toPath)
                         (FileChannel/open
                           (into-array OpenOption [StandardOpenOption/READ])))
        chunked-input (ChunkedNioFile. file-channel
                                       (.-offset hf)
                                       (.-length hf)
                                       (p/int (.-chunk-size hf)))]
    (send-chunked-body ch stream headers chunked-input)))

(defn- send-message
  [^Http2StreamChannel ch ^Http2Headers headers body]
  (log/trace "http2 send-message")
  (let [^Http2FrameStream stream (.stream ch)]
    (try
      (cond
        (or (nil? body)
            (identical? :aleph/omitted body))
        (do
          (log/debug "Body is nil or omitted")
          (let [header-frame (-> headers (DefaultHttp2HeadersFrame. true) (.stream stream))]
            (log/debug header-frame)
            (netty/write-and-flush ch header-frame)))

        (or
          (instance? String body)
          (instance? byte-array-class body)
          (instance? ByteBuffer body)
          (instance? ByteBuf body))
        (send-contiguous-body ch stream headers body)

        (instance? ChunkedInput body)
        (send-chunked-body ch stream headers body)

        (instance? RandomAccessFile body)
        (send-chunked-body ch stream headers
                           (ChunkedFile. ^RandomAccessFile body
                                         (p/int file/default-chunk-size)))

        (instance? File body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^File body (p/int file/default-chunk-size)))

        (instance? Path body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^FileChannel
                                            (FileChannel/open
                                              ^Path body
                                              (into-array OpenOption [StandardOpenOption/READ]))
                                            (p/int file/default-chunk-size)))

        (instance? HttpFile body)
        (send-http-file ch stream headers body)

        (instance? FileChannel body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^FileChannel body (p/int file/default-chunk-size)))

        (instance? FileRegion body)
        (if (or (-> ch (.pipeline) (.get SslHandler))
                (some-> ch (.parent) (.pipeline) (.get SslHandler)))
          (let [emsg (str "FileRegion not supported with SSL in Netty")
                ex (ex-info emsg {:ch ch :headers headers :body body})
                e (Http2FrameStreamException. stream Http2Error/PROTOCOL_ERROR ex)]
            (log/error e emsg)
            (netty/close ch))
          (send-file-region ch headers body))

        :else
        (let [class-name (StringUtil/simpleClassName body)]
          (try
            (send-streaming-body ch msg body)
            (catch Throwable e
              (log/error e "error sending body of type " class-name)
              (throw e)))))

      (catch Exception e
        (println "Error sending message" e)
        (log/error e "Error sending message")
        (throw (Http2FrameStreamException. (.stream ch)
                                           Http2Error/PROTOCOL_ERROR
                                           (ex-info "Error sending message" {:headers headers :body body} e)))))))

;; NOTE: can't be as vague about whether we're working with a channel or context in HTTP/2 code,
;; because we need access to the .stream method. We have a lot of code in aleph.netty that
;; branches based on the class (channel vs context), but that's not ideal. It's slower, and
;; writing to the channel vs the context means different things, anyway, they're not
;; usually interchangeable.
(defn req-preprocess
  [^Http2StreamChannel ch req responses]
  (println "req-preprocess fired")
  (println "ch class: " (StringUtil/simpleClassName (class ch)))
  #_(println "ch class reflect: " (clojure.reflect/type-reflect (class ch)))
  (println "req" (prn-str req))
  (flush)

  (try
    (let [body (:body req)
          parts (:multipart req)
          multipart? (some? parts)
          [req' body] (cond
                        ;; RFC #7231 4.3.8. TRACE
                        ;; A client MUST NOT send a message body...
                        (= :trace (:request-method req))
                        (do
                          (when (or (some? body) multipart?)
                            (log/warn "TRACE request body was omitted"))
                          [req nil])

                        (not multipart?)
                        [req body]

                        :else
                        (do
                          (println "HTTP/2 multipart not supported yet")
                          (s/put! responses
                                  (d/error-deferred (ex-info "HTTP/2 multipart not supported yet"
                                                             {:req req
                                                              :stream (.stream ch)})))
                          (netty/close ch))
                        #_(multipart/encode-request req' parts))
          headers (ring-map->netty-http2-headers req')]

      ;; Store message and/or original body if requested, for debugging purposes
      (when-let [save-message (get req :aleph/save-request-message)]
        (reset! save-message req'))
      (when-let [save-body (get req :aleph/save-request-body)]
        ;; might be different in case we use :multipart
        (reset! save-body body))

      (-> (netty/safe-execute ch (send-message ch headers body))
          (d/catch' (fn [e]
                      (log/error e "Error in req-preprocess")
                      (s/put! responses (d/error-deferred e))
                      (netty/close ch)))))

    ;; this will usually happen because of a malformed request
    (catch Throwable e
      (println "error in req-preprocess" e) (flush)
      (log/error e "Error in req-preprocess")
      (s/put! responses (d/error-deferred e))
      (netty/close ch))))


(comment
  (do
    (require '[aleph.http.client]
             '[clj-commons.byte-streams :as bs])
    (import '(io.netty.channel DefaultFileRegion)
            '(io.netty.handler.stream ChunkedFile ChunkedNioFile)
            '(java.net InetSocketAddress)
            '(java.nio.channels FileChannel)
            '(java.nio.file Files OpenOption Path Paths)
            '(java.nio.file.attribute FileAttribute)))

  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(println "http conn closed")}))

    @(conn {:request-method :get}))

  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(println "http conn closed")}))

    (let [body-string "Body test"
          fpath (Files/createTempFile "test" ".txt" (into-array FileAttribute []))
          ffile (.toFile fpath)
          _ (spit ffile body-string)
          fchan (FileChannel/open fpath (into-array OpenOption []))

          body
          #_body-string
          #_fpath
          #_ffile
          #_(RandomAccessFile. ffile "r")
          #_fchan
          #_(ChunkedFile. ffile)
          #_(ChunkedNioFile. fchan)
          (file/http-file ffile 1 6 4)
          #_(DefaultFileRegion. fchan 0 (.size fchan))
          ]

      (def result
        @(conn {:request-method :post
                :uri            "/post"
                :headers        {"content-type" "text/plain"}
                :body           body}))

      ;;(Files/deleteIfExists fpath)

      (some-> result :body bs/to-string)))
  )
