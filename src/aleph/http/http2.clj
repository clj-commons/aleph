(ns ^:no-doc aleph.http.http2
  "HTTP/2 functionality"
  (:require
    [aleph.http.common :as common]
    [aleph.http.file :as file]
    [aleph.http.multipart :as multipart]
    [aleph.netty :as netty]
    [clj-commons.primitive-math :as p]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.http.file HttpFile)
    (clojure.lang IPending)
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
      ChunkedNioFile)
    (io.netty.util.internal StringUtil)
    (java.io
      Closeable
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

(def ^:const max-allowed-frame-size 16777215) ; 2^24 - 1, see RFC 9113, SETTINGS_MAX_FRAME_SIZE
(def ^:dynamic *default-chunk-size* 16384) ; same as default SETTINGS_MAX_FRAME_SIZE

;; See https://httpwg.org/specs/rfc9113.html#ConnectionSpecific
(def invalid-headers #{"connection" "proxy-connection" "keep-alive" "upgrade"})

(let [illegal-arg (fn [^String emsg]
                    (let [ex (IllegalArgumentException. emsg)]
                      (log/error ex emsg)
                      (throw ex)))]
  (defn- add-header
    "Add a single header and value. The value can be a string or a collection of
     strings.

     Respects HTTP/2 rules. Strips invalid connection-related headers. Throws on
     nil header values. Throws if `transfer-encoding` is present, but not 'trailers'."
    [^Http2Headers h2-headers ^String header-name header-value]
    (log/debug "Adding HTTP header" header-name ": " header-value)
    (if (nil? header-name)
      (throw (IllegalArgumentException. "Header name cannot be nil"))
      (let [header-name (str/lower-case header-name)]       ; http2 requires lowercase headers
        (cond
          (nil? header-value)
          (illegal-arg (str "Invalid nil value for header '" header-name "'"))

          (invalid-headers header-name)
          (illegal-arg (str "Forbidden HTTP/2 header: \"" header-name "\""))

          ;; See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Transfer-Encoding
          (and (.equals "transfer-encoding" header-name)
               (not (.equals "trailers" header-value)))
          (illegal-arg "Invalid value for 'transfer-encoding' header. Only 'trailers' is allowed.")

          (sequential? header-value)
          (.add h2-headers ^CharSequence header-name ^Iterable header-value)

          :else
          (.add h2-headers ^CharSequence header-name ^Object header-value))))))

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

(defn netty-http2-headers->map
  "Returns a map of Ring headers from a Netty Http2Headers object.

   Includes pseudo-headers."
  [^Http2Headers headers]
  (loop [iter (.iterator headers)
         result {}]
    (if (.hasNext iter)
      (let [entry (.next iter)]
        (recur iter
               (assoc result
                      (.toString ^CharSequence (key entry))
                      (.toString ^CharSequence (val entry)))))
      result)))

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
  "Write out a msg and a body that is, or can be turned into, a ByteBuf.

   Works with strings, byte arrays, ByteBuffers, ByteBufs, nil, and
   anything supported by byte-streams' `convert`"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body]
  (let [body-bb (netty/to-byte-buf ch body)]
    (try-set-content-length! headers (.readableBytes body-bb))

    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch (-> body-bb (DefaultHttp2DataFrame. true) (.stream stream)))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers ^ChunkedInput body]
  (log/trace "Sending ChunkedInput")
  (let [len (.length body)]
    (when (p/>= len 0)
      (try-set-content-length! headers len)))

  (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
  (netty/write-and-flush ch (-> body (Http2DataChunkedInput. stream))))

(defn send-file-region
  "Write out a msg and a FileRegion body. Uses the fast zero-copy .transferTo()

   WARNING: Netty does not support this with its SslHandler. While there are
   some OSes/NICs that can support SSL and zero-copy, Netty does not. This means
   FileRegions can only be used with non-SSL connections."
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

(defn- write-available-sequence-data
  "Writes out available data from anything sequential, and returns any
   unrealized remainder (for lazy sequences)."
  [^Http2StreamChannel ch s]
  (let [buf (netty/allocate ch)
        lazy? (instance? IPending s)]
    (loop [s s]
      (cond
        ;; lazy and no data available yet - write out buf and move on
        (and lazy? (not (realized? s)))
        (do
          (netty/write-and-flush ch buf)
          s)

        ;; If we're out of data, write out the buf, and return nil
        (empty? s)
        (do
          (netty/write-and-flush ch buf)
          nil)

        ;; if data is ready, append it to the buf and recur
        (or (not lazy?) (realized? s))
        (let [x (first s)]
          (netty/append-to-buf! buf x)
          (recur (rest s)))

        :else
        (do
          (netty/write-and-flush ch buf)
          s)))))

(defn- end-of-stream-frame
  "Returns an empty data frame with end-of-stream set to true."
  [^Http2FrameStream stream]
  (-> (DefaultHttp2DataFrame. true)
      (.stream stream)))

(defn send-streaming-body
  "Write out a msg and a body that's streamable"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body chunk-size]

  ;; write out header frame first
  (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))

  (if-let [body' (if (sequential? body)
                   ;; write out all the data we have already, and return the rest
                   (->> body
                        (map common/coerce-element)
                        (write-available-sequence-data ch))
                   (do
                     (netty/flush ch)
                     body))]

    (let [d (d/deferred)
          src (common/body-byte-buf-stream d ch body' chunk-size)
          sink (netty/sink ch
                           false
                           (fn [^ByteBuf buf]
                             (-> buf (DefaultHttp2DataFrame.) (.stream stream))))

          ;; mustn't close over body' if NOT a stream, can hold on to data too long
          ch-close-handler (if (s/stream? body')
                             #(s/close! body')
                             #(s/close! src))]

      (s/connect src sink)

      ;; set up ch close handler
      (-> ch
          netty/channel
          .closeFuture
          netty/wrap-future
          (d/chain' (fn [_] (ch-close-handler))))

      ;; set up sink close handler
      (s/on-closed sink
                   (fn []
                     (when (instance? Closeable body)
                       (.close ^Closeable body))

                     (.execute (-> ch netty/channel .eventLoop)
                               #(d/success! d
                                            (netty/write-and-flush ch (end-of-stream-frame stream))))))
      d)

    ;; otherwise, no data left in body', so just send an empty data frame
    (netty/write-and-flush ch (end-of-stream-frame stream))))

(defn- send-message
  "Given Http2Headers and a body, determines the best way to write the body to the stream channel"
  [^Http2StreamChannel ch ^Http2Headers headers body chunk-size file-chunk-size]
  (log/trace "http2 send-message")
  (let [^Http2FrameStream stream (.stream ch)]
    (try
      (cond
        (or (nil? body)
            (identical? :aleph/omitted body))
        (do
          (log/trace "Body is nil or omitted")
          (let [header-frame (-> headers (DefaultHttp2HeadersFrame. true) (.stream stream))]
            (log/debug header-frame)
            (netty/write-and-flush ch header-frame)))

        (or
          (instance? String body)
          (instance? ByteBuf body)
          (instance? ByteBuffer body)
          (instance? byte-array-class body))
        (send-contiguous-body ch stream headers body)

        (instance? ChunkedInput body)
        (send-chunked-body ch stream headers body)

        (instance? RandomAccessFile body)
        (send-chunked-body ch stream headers
                           (ChunkedFile. ^RandomAccessFile body
                                         ^int file-chunk-size))

        (instance? File body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^File body ^int file-chunk-size))

        (instance? Path body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^FileChannel
                                            (FileChannel/open
                                              ^Path body
                                              (into-array OpenOption [StandardOpenOption/READ]))
                                            ^int file-chunk-size))

        (instance? HttpFile body)
        (send-http-file ch stream headers body)

        (instance? FileChannel body)
        (send-chunked-body ch stream headers
                           (ChunkedNioFile. ^FileChannel body ^int file-chunk-size))

        (instance? FileRegion body)
        (if (or (-> ch (.pipeline) (.get ^Class SslHandler))
                (some-> ch (.parent) (.pipeline) (.get ^Class SslHandler)))
          (let [emsg (str "FileRegion not supported with SSL in Netty")
                ex (ex-info emsg {:ch ch :headers headers :body body})
                e (Http2FrameStreamException. stream Http2Error/PROTOCOL_ERROR ex)]
            (log/error e emsg)
            (netty/close ch))
          (send-file-region ch headers body))

        :else
        (try
          (send-streaming-body ch stream headers body chunk-size)
          (catch Throwable e
            (log/error e "Error sending body of type " (StringUtil/simpleClassName body))
            (throw e))))

      (catch Exception e
        (log/error e "Error sending message")
        (throw (Http2FrameStreamException. (.stream ch)
                                           Http2Error/PROTOCOL_ERROR
                                           (ex-info "Error sending message" {:headers headers :body body} e)))))))

;; NOTE: can't be as vague about whether we're working with a channel or context in HTTP/2 code,
;; because we need access to the .stream method. We have a lot of code in aleph.netty that
;; branches based on the class (channel vs context), but that's not ideal. It's slower, and
;; writing to the channel vs the context means different things, anyway, they're not
;; usually interchangeable.
(defn send-request
  [^Http2StreamChannel ch req response]
  (log/trace "http2 send-request fired")

  (when (multipart/is-multipart? req)
    ;; shouldn't reach at the moment, but just in case
    (throw (ex-info "HTTP/2 code path multipart not supported"
                    {:req req
                     :stream (.stream ch)})))

  (try
    (let [body (if (= :trace (:request-method req))
                 ;; RFC #7231 4.3.8. TRACE
                 ;; A client MUST NOT send a message body...
                 (do
                   (when (some? (:body req))
                     (log/warn "Non-nil TRACE request body was removed"))
                   nil)
                 (:body req))
          headers (ring-map->netty-http2-headers req)
          chunk-size (or (:chunk-size req) *default-chunk-size*)
          file-chunk-size (p/int (or (:chunk-size req) file/*default-file-chunk-size*))]

      ;; Store message and/or original body if requested, for debugging purposes
      (when-let [save-message (get req :aleph/save-request-message)]
        (reset! save-message req))
      (when-let [save-body (get req :aleph/save-request-body)]
        ;; might be different in case we use :multipart
        (reset! save-body body))

      (-> (netty/safe-execute ch (send-message ch headers body chunk-size file-chunk-size))
          (d/catch' (fn [e]
                      (log/error e "Error in http2 req-preprocess")
                      (d/error! response e)
                      (netty/close ch)))))

    ;; this will usually happen because of a malformed request
    (catch Throwable e
      (log/error e "Error in http2 req-preprocess")
      (d/error! response e)
      (netty/close ch))))


(comment
  (do
    (require '[aleph.http.client]
             '[aleph.http.multipart]
             '[clj-commons.byte-streams :as bs])
    (import '(io.netty.channel DefaultFileRegion)
            '(io.netty.handler.stream ChunkedFile ChunkedNioFile)
            '(java.net InetSocketAddress)
            '(java.nio.channels FileChannel)
            '(java.nio.file Files OpenOption Path Paths)
            '(java.nio.file.attribute FileAttribute)))

  ;; basic test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http1]}))

    (def result @(conn {:request-method :get
                        ;;:raw-stream?    true
                        })))

  ;; different body types test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")}))

    (let [body-string "Body test"
          fpath (Files/createTempFile "test" ".txt" (into-array FileAttribute []))
          ffile (.toFile fpath)
          _ (spit ffile body-string)
          fchan (FileChannel/open fpath (into-array OpenOption []))
          aleph-1k (repeat 1000 \ℵ)
          aleph-20k (repeat 20000 \ℵ)
          aleph-1k-string (apply str aleph-1k)

          body
          body-string
          #_fpath
          #_ffile
          #_(RandomAccessFile. ffile "r")
          #_fchan
          #_(ChunkedFile. ffile)
          #_(ChunkedNioFile. fchan)
          #_(file/http-file ffile 1 6 4)
          #_(DefaultFileRegion. fchan 0 (.size fchan))
          #_(seq body-string)
          #_[:foo :bar :moop]
          #_aleph-20k
          #_[aleph-1k-string aleph-1k-string]]


      (def result
        @(conn {:request-method :post
                :uri            "/post"
                :headers        {"content-type" "text/plain"}
                :body           body
                ;;:raw-stream?    true
                }))

      (some-> result :body bs/to-string)))

  ;; multipart test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions  [:http2 :http1]}))

    (def result
      @(conn {:request-method :post
              :uri            "/post"
              :headers        {"content-type" "text/plain"}
              :multipart      [{:part-name "part1"
                                :content   "content1"
                                :charset   "UTF-8"}
                               {:part-name "part2"
                                :content   "content2"}
                               {:part-name "part3"
                                :content   "content3"
                                :mime-type "application/json"}]}))
    (some-> result :body bs/to-string))

  ;; multiple simultaneous requests test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")}))

    (let [req {:request-method :post
               :uri            "/post"
               :headers        {"content-type" "text/plain"}
               :body           "Body test"
               :raw-stream?    true
               }]
      (def results (map conn (repeat 3 req)))
      @(nth results 2)))
  )
