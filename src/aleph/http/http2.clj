(ns ^:no-doc aleph.http.http2
  "HTTP/2 functionality"
  (:require
    [aleph.http.common :as common]
    [aleph.http.file :as file]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clj-commons.primitive-math :as p]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.http AlephChannelInitializer)
    (aleph.http.file HttpFile)
    (clojure.lang IPending)
    (io.netty.buffer ByteBuf)
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelPipeline
      FileRegion)
    (io.netty.handler.codec.http
      QueryStringDecoder)
    (io.netty.handler.codec.http2
      DefaultHttp2DataFrame
      DefaultHttp2Headers
      DefaultHttp2HeadersFrame
      Http2DataChunkedInput
      Http2DataFrame
      Http2Error
      Http2Exception
      Http2FrameCodecBuilder
      Http2FrameLogger
      Http2FrameStream
      Http2FrameStreamException
      Http2GoAwayFrame
      Http2Headers
      Http2HeadersFrame
      Http2MultiplexHandler
      Http2ResetFrame
      Http2Settings
      Http2StreamChannel)
    (io.netty.handler.logging LoggingHandler)
    (io.netty.handler.ssl SslHandler)
    (io.netty.handler.stream
      ChunkedFile
      ChunkedInput
      ChunkedNioFile
      ChunkedWriteHandler)
    (io.netty.util
      AsciiString
      ReferenceCounted)
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
      StandardOpenOption)
    (java.util.concurrent
      ConcurrentHashMap
      RejectedExecutionException)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (Class/forName "[B"))

(def ^:const max-allowed-frame-size 16777215) ; 2^24 - 1, see RFC 9113, SETTINGS_MAX_FRAME_SIZE
(def ^:dynamic *default-chunk-size* 16384) ; same as default SETTINGS_MAX_FRAME_SIZE

;; TODO: optimize. Either non-concurrent HashMap, regex, or with a fn
;; See https://httpwg.org/specs/rfc9113.html#ConnectionSpecific
;;(def invalid-headers #{"connection" "proxy-connection" "keep-alive" "upgrade"})
(def ^:private invalid-headers (set (map #(AsciiString/cached ^String %)
                                         ["connection" "proxy-connection" "keep-alive" "upgrade"])))
(def ^:private ^AsciiString te-header-name (AsciiString/cached "transfer-encoding"))

(def ^:private ^ConcurrentHashMap cached-header-names
  "No point in lower-casing the same strings over and over again."
  (ConcurrentHashMap. 128))

(defn- illegal-arg
  "Logs an error message, then throws an IllegalArgumentException with that error message."
  [^String emsg]
  (let [ex (IllegalArgumentException. emsg)]
    (log/error ex emsg)
    (throw ex)))

(defn- add-header
  "Add a single header and value. The value can be a string or a collection of
   strings.

   Header names are converted to lower-case AsciiStrings. Values are left as-is,
   but it's up to the user to ensure they are valid. For more info, see
   https://www.rfc-editor.org/rfc/rfc9113.html#section-8.2.1

   Respects HTTP/2 rules. All headers will be made lower-case. Throws on
   invalid connection-related headers. Throws on nil header values. Throws if
   `transfer-encoding` is present, but is not equal to 'trailers'."
  [^Http2Headers h2-headers ^String header-name header-value]
  (log/debug (str "Adding HTTP header: " header-name ": " header-value))

  ;; Also checked by Netty, but we want to avoid work, so we check too
  (if (nil? header-name)
    (illegal-arg "Header name cannot be nil")

    ;; Technically, Ring requires lower-cased headers, but there's no guarantee, and this is
    ;; probably faster, since most users won't be caching.
    (let [header-name (if-some [cached (.get cached-header-names header-name)]
                        cached
                        (let [lower-cased (-> header-name (name) (AsciiString.) (.toLowerCase))]
                          (.put cached-header-names header-name lower-cased)
                          lower-cased))]
      (cond
        ;; Will be checked by Netty
        ;;(nil? header-value)
        ;;(illegal-arg (str "Invalid nil value for header '" header-name "'"))

        (invalid-headers header-name)
        (illegal-arg (str "Forbidden HTTP/2 header: \"" header-name "\""))

        ;; See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Transfer-Encoding
        (and (.equals te-header-name header-name)
             (not (.equals "trailers" header-value)))
        (illegal-arg "Invalid value for 'transfer-encoding' header. Only 'trailers' is allowed.")

        (sequential? header-value)
        (.add h2-headers ^CharSequence header-name ^Iterable header-value)

        :else
        (.add h2-headers ^CharSequence header-name ^Object header-value)))))

(defn- ring-map->netty-http2-headers
  "Builds a Netty Http2Headers object from a Ring map."
  ^DefaultHttp2Headers
  [m is-request?]
  (let [h2-headers (DefaultHttp2Headers. true)]

    (if is-request?
      (-> h2-headers
          (.method (-> m (get :request-method) name str/upper-case))
          (.scheme (-> m (get :scheme) name))
          (.authority (:authority m))
          (.path (str (get m :uri)
                      (when-let [q (get m :query-string)]
                        (str "?" q)))))
      (let [status (get m :status)]
        (if (string? status)
          (.status h2-headers status)
          (.status h2-headers (Integer/toString status)))))

    ;; Technically, missing :headers is a violation of the Ring SPEC, but kept
    ;; for backwards compatibility
    (when-let [headers (get m :headers)]
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

   Will skip if it's a response and the status code is 1xx or 204. (RFC 9110 §8.6)
   Negative length values are ignored."
  ^Http2Headers
  [^Http2Headers headers ^long length]
  (when-not (.get headers "content-length")
    (if (some? (.status headers))
      ;; TODO: consider switching to netty's HttpResponseStatus and HttpStatusClass for clarity
      (let [code (-> headers (.status) (.toString) (Long/parseLong))]
        (when-not (or (p/== 204 code)
                      (and (p/<= 100 code)
                           (p/<= code 199)))
          (.setLong headers "content-length" length)))
      (.setLong headers "content-length" length))))


(defn send-contiguous-body
  "Write out a msg and a body that is, or can be turned into, a ByteBuf.

   Works with strings, byte arrays, ByteBuffers, ByteBufs, nil, and
   anything supported by byte-streams' `convert`"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body]
  (log/trace "http2 send-contiguous-body")
  (let [body-bb (netty/to-byte-buf ch body)]
    (try-set-content-length! headers (.readableBytes body-bb))

    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch (-> body-bb (DefaultHttp2DataFrame. true) (.stream stream)))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers ^ChunkedInput body]
  (log/trace "http2 send-chunked-body")
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
  (log/trace "http2 send-file-region")
  (let [stream (.stream ch)]
    (try-set-content-length! headers (.count fr))
    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch fr)))

(defn send-http-file
  "Send an HttpFile using ChunkedNioFile"
  [ch stream headers ^HttpFile hf]
  (log/trace "http2 send-http-file")
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
  (log/trace "http2 send-streaming-body")

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
  ([^Http2StreamChannel ch ^Http2Headers headers body]
   (send-message ch headers body *default-chunk-size* file/*default-file-chunk-size*))
  ([^Http2StreamChannel ch ^Http2Headers headers body chunk-size file-chunk-size]
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
                                            (ex-info "Error sending message" {:headers headers :body body} e))))))))

;; NOTE: can't be as vague about whether we're working with a channel or context in HTTP/2 code,
;; because we need access to the .stream method. We have a lot of code in aleph.netty that
;; branches based on the class (channel vs context), but that's not ideal. It's slower, and
;; writing to the channel vs the context means different things, anyway, they're not
;; usually interchangeable.
(defn send-request
  [^Http2StreamChannel ch req response]
  (log/trace "http2 send-request fired")

  (try
    (let [body (if (= :trace (:request-method req))
                 ;; RFC #7231 4.3.8. TRACE
                 ;; A client MUST NOT send a message body...
                 (do
                   (when (some? (:body req))
                     (log/warn "Non-nil TRACE request body was removed"))
                   nil)
                 (:body req))
          headers (ring-map->netty-http2-headers req true)
          chunk-size (or (:chunk-size req) *default-chunk-size*)
          file-chunk-size (p/int (or (:chunk-size req) file/*default-file-chunk-size*))]

      ;; Store message and/or original body if requested, for debugging purposes
      (when-let [save-message (get req :aleph/save-request-message)]
        (reset! save-message req))
      (when-let [save-body (get req :aleph/save-request-body)]
        ;; might be different in case we use :multipart
        (reset! save-body body))

      (-> (netty/safe-execute ch
            (send-message ch headers body chunk-size file-chunk-size))
          (d/catch' (fn [e]
                      (log/error e "Error in http2 send-request")
                      (d/error! response e)
                      (netty/close ch)))))

    ;; this will usually happen because of a malformed request
    (catch Throwable e
      (log/error e "Error in http2 req-preprocess")
      (d/error! response e)
      (netty/close ch))))

(let [[server-name date-name content-type]
      (map #(AsciiString. ^CharSequence %) ["server" "date" "content-type"])]

  (defn send-response
    "Converts the Ring response map to Netty Http2Headers, extracts the body,
     and then sends both to Netty to be sent out over the wire."
    [^Http2StreamChannel ch error-handler head-request? rsp]
    (log/trace "http2 send-response")

    (try
      (let [body (if head-request?
                   ;; https://www.rfc-editor.org/rfc/rfc9110#section-9.3.2
                   (do
                     (when (some? (:body rsp))
                       (log/warn "Non-nil HEAD response body was removed"))
                     :aleph/omitted)
                   (:body rsp))
            headers (ring-map->netty-http2-headers rsp false)
            chunk-size (or (:chunk-size rsp) *default-chunk-size*)
            file-chunk-size (p/int (or (:chunk-size rsp) file/*default-file-chunk-size*))]

        ;; Add default headers if they're not already present
        (when-not (.contains headers ^CharSequence server-name)
          (.set headers ^CharSequence server-name common/aleph-server-header))

        (when-not (.contains headers ^CharSequence date-name)
          (.set headers ^CharSequence date-name (common/date-header-value (.eventLoop ch))))

        (when (.equals "text/plain" (.get headers ^CharSequence content-type))
          (.set headers ^CharSequence content-type "text/plain; charset=UTF-8"))

        (log/debug "Sending to Netty. Headers:" (pr-str headers) " - Body class:" (class body))

        (-> (netty/safe-execute ch
              (send-message ch headers body chunk-size file-chunk-size))
            (d/catch' (fn [e]
                        (log/error e "Error in http2 send-message")
                        (netty/close ch)))))

      (catch Exception e
        (log/error e "Error in http2 send-response")
        (log/error "Stack trace:" (.printStackTrace e))
        (throw e)))))



(comment
  ;; parsing authority probably not needed for server-name/port,
  ;; but may be useful for generating "Host" header???
  (def ^:const ^:private ^int at-int (int (.charValue \@)))
  (def ^:const ^:private ^int colon-int (int (.charValue \:)))
  (let [scheme (keyword (.scheme headers))
        ;; parsing :authority is 10x faster than using URI
        authority (.toString (.authority headers))
        at-index (.indexOf authority at-int)
        colon-index (.indexOf authority colon-int at-index)]

    {:server-name       (.subSequence authority
                                      (p/inc at-index)
                                      (if (p/== colon-index -1)
                                        (.length authority)
                                        colon-index))
     :server-port       (if (p/== colon-index -1)
                          (if (= scheme :https) 443 80)
                          (Integer/parseInt (.subSequence authority
                                                          (p/inc colon-index)
                                                          (.length authority)) 10))})
  )


(defn- netty->ring-request
  "Given Netty Http2Headers and a body stream, returns a Ring request map"
  [ch ^Http2Headers headers body complete]
  ;; The :path pseudo-header is not the same as the Ring SPEC, it has to be split.
  (let [qsd (QueryStringDecoder. (-> headers (.path) (.toString)))
        path (.rawPath qsd)
        query-string (.rawQuery qsd)]
    (cond->
      {:request-method    (-> headers (.method) (.toString) (.toLowerCase) keyword)
       :scheme            (-> headers (.scheme) (.toString) keyword)
       :path              path
       :uri               path                              ; not really a URI
       :server-name       (netty/channel-server-name ch)    ; is this best?
       :server-port       (netty/channel-server-port ch)
       :remote-addr       (netty/channel-remote-address ch)
       :headers           (netty-http2-headers->map headers)
       :body              body
       ;;:trailers          (d/deferred)

       :protocol          "HTTP/2.0"
       :aleph/keep-alive? true                              ; not applicable to HTTP/2, but here for compatibility
       :aleph/complete    complete}

      (not (.isEmpty query-string))
      (assoc :query-string query-string))))

(defn- server-handler
  "Returns a ChannelInboundHandler that processes inbound Netty Http2 frames,
   converts them, and calls the user handler with them. It then converts the
   handler's Ring response into Netty Http2 objects and sends them on the
   outbound pipeline."
  [^Http2StreamChannel ch
   handler
   raw-stream?
   rejected-handler
   error-handler
   executor
   buffer-capacity]
  (log/trace "server-handler")
  #_(log/debug "server-handler args" {:ch ch
                                    :handler handler
                                    :raw-stream? raw-stream?
                                    :rejected-handler rejected-handler
                                    :error-handler error-handler
                                    :executor executor
                                    :buffer-capacity buffer-capacity})
  (let [buffer-capacity (long buffer-capacity)
        complete (d/deferred) ; realized when this stream is done, regardless of success/error

        ;; if raw, we're returning a stream of ByteBufs, if not, we return byte arrays
        body-stream
        (doto (if raw-stream?
                (netty/buffered-source ch #(.readableBytes ^ByteBuf %) buffer-capacity)
                (netty/buffered-source ch #(alength ^bytes %) buffer-capacity))
              (s/on-closed #(d/success! complete true)))

        body (if raw-stream?
               body-stream
               (bs/to-input-stream body-stream))

        ;; TODO: passing errors to the stream is problematic
        ;; Not clear how byte-streams will propagate errors when converting for
        ;; non-raw streams (what happens when bs gets an Exception? Should it
        ;; close the stream? Throw mysterious IOExceptions?)
        ;; A user-supplied error handler may be better, either in here, or as part of the netty pipeline
        handle-error
        (let [close-body-handler (fn [_] (s/close! body-stream))]
          (fn handle-error [ex]
            (log/error ex "Exception caught in HTTP/2 stream server handler" {:raw-stream? raw-stream?})
            (d/error! complete ex)
            (s/close! body-stream)
            #_(-> (s/put! body-stream ex)                     ; FIXME?
                (d/on-realized close-body-handler close-body-handler))))

        ;; TODO: probably need to stop logging GOAWAY and RST_STREAM as error - more like info or warn
        handle-shutdown-frame
        (fn handle-shutdown-frame [evt]
          ;; Sadly no common error parent class for Http2ResetFrame and Http2GoAwayFrame
          (when (or (instance? Http2ResetFrame evt)
                    (instance? Http2GoAwayFrame evt))
            (let [stream-id (-> ch (.stream) (.id))
                  error-code (if (instance? Http2ResetFrame evt)
                               (.errorCode ^Http2ResetFrame evt)
                               (.errorCode ^Http2GoAwayFrame evt))
                  msg (if (instance? Http2ResetFrame evt)
                        (str "Received RST_STREAM from peer, closing stream " stream-id ". HTTP/2 error code: " error-code ".")
                        (str "Received GOAWAY from peer, closing connection and all streams. HTTP/2 error code: " error-code "."))
                  no-error? (p/== error-code (.code Http2Error/NO_ERROR))]
              ;; Was there an error, or does the peer just want to shut down the stream/conn?
              (if no-error?
                (do
                  (log/info msg)
                  (s/close! body-stream))
                (handle-error (Http2Exception. (Http2Error/valueOf error-code) msg))))))]

    (netty/channel-inbound-handler
      ;;:channel-active
      ;;([_ ctx]
      ;; (netty/ensure-dynamic-classloader)
      ;; (.fireChannelActive ctx))

      :exception-caught
      ([_ ctx ex]
       (handle-error ex))

      :channel-inactive
      ([_ ctx]
       (s/close! body-stream)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
       (cond
         (instance? Http2HeadersFrame msg)
         ;; TODO: support trailers?
         (let [headers (.headers ^Http2HeadersFrame msg)
               ;;body (if raw-stream? body-stream (bs/to-input-stream body-stream))
               ring-req (netty->ring-request ch headers body complete)
               is-head? (= :head (:request-method ring-req))]

           (log/debug "Received HTTP/2 request"
                      (pr-str (assoc ring-req :body (class (:body ring-req)))))

           (when (.isEndStream ^Http2HeadersFrame msg)
             (s/close! body-stream))

           (-> (if executor
                 ;; handle request on a separate thread
                 (try
                   (d/future-with executor
                     (log/debug "Dispatching request to user handler"
                                (assoc ring-req :body (class (:body ring-req))))
                     (handler ring-req))
                   (catch RejectedExecutionException e
                     (if rejected-handler
                       (try
                         (rejected-handler ring-req)
                         (catch Throwable e
                           (error-handler e)))
                       {:status  503
                        :headers {"content-type" "text/plain"}
                        :body    "503 Service Unavailable"})))

                 ;; else handle it inline (hope you know what you're doing!)
                 (try
                   (log/warn "Running user handler inline")
                   (handler ring-req)
                   (catch Throwable e
                     (error-handler e))))
               (d/catch' error-handler)
               (d/chain'
                 (fn send-http-response [ring-resp]
                   (log/trace "send-http-response")
                   (log/debug "Response from user handler"
                              (pr-str (assoc ring-resp :body (class (:body ring-resp)))))

                   (send-response ch
                                  error-handler
                                  is-head?
                                  (cond
                                    (map? ring-resp)
                                    ring-resp

                                    (nil? ring-resp)
                                    {:status 204}

                                    :else
                                    (error-handler (common/invalid-value-exception ring-req ring-resp))))))))

         (instance? Http2DataFrame msg)
         (let [content (.content ^Http2DataFrame msg)]
           ;; skip empty bytebufs
           (when (p/> (.readableBytes content) 0)
             (netty/put! (.channel ctx)
                         body-stream
                         (if raw-stream?
                           content
                           (netty/buf->array content))))

           (when-not raw-stream?
             (.release ^ReferenceCounted msg))

           (when (.isEndStream ^Http2DataFrame msg)
             (s/close! body-stream)))

         :else
         (do
           (log/debug "Unhandled message in server-handler" msg)
           (.fireChannelRead ctx msg))))

      :user-event-triggered
      ([_ ctx evt]
       (handle-shutdown-frame evt)
       (.fireUserEventTriggered ctx evt)))))

(defn setup-stream-pipeline
  "Set up the pipeline for an HTTP/2 stream channel"
  [^ChannelPipeline p handler is-server? proxy-options logger pipeline-transform]
  (log/trace "setup-stream-pipeline called")

  ;; necessary for multipart support in HTTP/2?
  #_(.addLast p
              "stream-frame-to-http-object"
              ^ChannelHandler stream-frame->http-object-codec)
  (.addLast p
            "streamer"
            ^ChannelHandler (ChunkedWriteHandler.))

  (when is-server?
    ;; TODO: add continue handler for server-side
    )

  (.addLast p
            "handler"
            ^ChannelHandler
            handler)

  (when (some? proxy-options)
    (throw (IllegalArgumentException. "Proxying HTTP/2 messages not supported yet")))

  (common/add-non-http-handlers p logger pipeline-transform)

  (common/add-exception-handler p "stream-ex-handler")

  (log/trace "Added all stream handlers")
  (log/debug "Stream chan pipeline:" p)

  p)

(def ^:private client-inbound-handler
  "For the client, the inbound handler will probably never get used.
   Servers will rarely initiate streams, because PUSH_PROMISE is effectively
   deprecated. Responses to client-initiated streams are set elsewhere
   (even if it's the same handler fn)."
  (netty/channel-inbound-handler
    {:channel-active
     ([_ ctx]
      (log/error "Cannot currently handle peer-initiated streams. (You must override this handler if receiving server-pushed streams.) Closing channel.")
      (netty/close ctx))}))

(defn setup-conn-pipeline
  "Set up pipeline for an HTTP/2 connection channel. Works for both server
   and client."
  [{:keys
    [^ChannelPipeline pipeline
     ^LoggingHandler logger
     idle-timeout
     ^ChannelHandler handler
     is-server?
     raw-stream?
     rejected-handler
     error-handler
     executor
     http2-settings
     request-buffer-size
     proxy-options
     pipeline-transform]
    :or
    {idle-timeout        0
     http2-settings      (Http2Settings/defaultSettings)
     pipeline-transform  identity
     request-buffer-size 16384
     error-handler       common/error-response}
    :as opts}]
  (log/trace "setup-conn-pipeline fired")
  (let [
        log-level (some-> logger (.level))
        http2-frame-codec (let [builder (if is-server?
                                          (Http2FrameCodecBuilder/forServer)
                                          (Http2FrameCodecBuilder/forClient))]
                            (when log-level
                              (.frameLogger builder (Http2FrameLogger. log-level)))
                            (-> builder
                                (.initialSettings ^Http2Settings http2-settings)
                                (.validateHeaders true)
                                (.build)))
        stream-chan-initializer (AlephChannelInitializer.
                                  #_netty/ensure-dynamic-classloader
                                  (fn [^Channel ch]
                                    (setup-stream-pipeline (.pipeline ch)
                                                           (if is-server?
                                                             (server-handler ch
                                                                             handler
                                                                             raw-stream?
                                                                             rejected-handler
                                                                             error-handler
                                                                             executor
                                                                             request-buffer-size)
                                                             client-inbound-handler)
                                                           is-server?
                                                           proxy-options
                                                           logger
                                                           pipeline-transform)))
        multiplex-handler (Http2MultiplexHandler. stream-chan-initializer)]

    (-> pipeline
        (common/add-idle-handlers idle-timeout)
        (.addLast "http2-frame-codec" http2-frame-codec)
        (.addLast "multiplex" multiplex-handler)
        ;; TODO: add handler for conn-level frames?
        (common/add-exception-handler "conn-ex-handler"))

    (log/debug "Conn chan pipeline:" pipeline)
    pipeline))



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

  ;; NB: postman-echo.com ALWAYS prefers http2, regardless of your order,
  ;; which is ironic, since the Postman app doesn't support sending http2 :P

  ;; basic test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

    (def result @(conn {:request-method :get
                        ;;:raw-stream?    true
                        })))

  ;; different body types test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

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
                :http-versions [:http2]
                ;;:raw-stream?    true
                }))

      (some-> result :body bs/to-string)))

  ;; multipart test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions  [:http2]}))

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
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

    (let [req {:request-method :post
               :uri            "/post"
               :headers        {"content-type" "text/plain"}
               :body           "Body test"
               :raw-stream?    true
               }]
      (def results (map conn (repeat 3 req)))
      @(nth results 2)))

  ;; compression test - don't support automatic decompression yet
  #_(do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http1]}))

    (def result @(conn {:request-method :get
                        :uri           "/gzip" #_ "/deflate"
                        ;; FWIW, postman-echo.com ignores the accept-encoding header below
                        :headers       {"accept-encoding" "gzip"}
                        :body          "ABC123"}))

    (some-> result :body bs/to-string))


  )
