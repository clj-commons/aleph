(ns ^:no-doc aleph.http.core
  "HTTP/1.1 functionality. Despite the name, this is only for HTTP/1, and
   kept for backwards compatibility. HTTP/2 is in aleph.http.http2. Shared
   code is in aleph.http.common. Shared client and server code can be found
   in their respective namespaces."
  (:require
    [aleph.http.common :as common]
    [aleph.http.file :as file]
    [aleph.netty :as netty]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [potemkin :as p])
  (:import
    (aleph.http.file
      HttpFile)
    (io.netty.buffer
      ByteBuf)
    (io.netty.channel
      Channel
      ChannelFuture
      ChannelFutureListener
      ChannelHandlerContext
      DefaultFileRegion)
    (io.netty.handler.codec.http
      DefaultFullHttpRequest
      DefaultHttpContent
      DefaultHttpRequest
      DefaultHttpResponse
      DefaultLastHttpContent
      HttpChunkedInput
      HttpContent
      HttpHeaders
      HttpMessage
      HttpMethod
      HttpRequest
      HttpResponse
      HttpResponseStatus
      HttpUtil
      HttpVersion
      LastHttpContent)
    (io.netty.handler.stream
      ChunkedFile
      ChunkedInput
      ChunkedWriteHandler)
    (io.netty.util AsciiString)
    (io.netty.util.internal StringUtil)
    (java.io
      Closeable
      File
      RandomAccessFile)
    (java.nio
      ByteBuffer)
    (java.nio.file
      Path)
    (java.util.concurrent
      ConcurrentHashMap)
    (java.util.concurrent.atomic
      AtomicBoolean)))

(def non-standard-keys
  (let [ks ["Content-MD5"
            "ETag"
            "WWW-Authenticate"
            "X-XSS-Protection"
            "X-WebKit-CSP"
            "X-UA-Compatible"
            "X-ATT-DeviceId"
            "DNT"
            "P3P"
            "TE"]]
    (zipmap
      (map str/lower-case ks)
      (map #(AsciiString. ^CharSequence %) ks))))

(def ^ConcurrentHashMap cached-header-keys (ConcurrentHashMap. 128))

(defn ^:deprecated normalize-header-key
  "Normalizes a header key to `Ab-Cd` format.

   NB: This is illegal for HTTP/2+, which requires all header names be
   lower-case. Technically, the Ring spec also requires lower-cased headers,
   but this is kept for backwards-compatibility."
  [s]
  (if-let [s' (.get cached-header-keys s)]
    s'
    (let [s' (str/lower-case (name s))
          s' (or
               (non-standard-keys s')
               (->> (str/split s' #"-")
                    (map str/capitalize)
                    (str/join "-")
                    (AsciiString.)))]

      ;; in practice this should never happen, so we
      ;; can be stupid about cache expiration
      (when (< 10000 (.size cached-header-keys))
        (.clear cached-header-keys))

      (.put cached-header-keys s s')
      s')))

(p/def-map-type HeaderMap
  [^HttpHeaders headers
   added
   removed
   mta]
  (meta [_]
    mta)
  (with-meta [_ m]
    (HeaderMap.
      headers
      added
      removed
      m))
  (keys [_]
    (set/difference
      (set/union
        (set (map str/lower-case (.names headers)))
        (set (keys added)))
      (set removed)))
  (assoc [_ k v]
    (HeaderMap.
      headers
      (assoc added k v)
      (disj removed k)
      mta))
  (dissoc [_ k]
    (HeaderMap.
      headers
      (dissoc added k)
      (conj (or removed #{}) k)
      mta))
  (get [_ k default-value]
    (if (contains? removed k)
      default-value
      (if-let [e (find added k)]
        (val e)
        (let [k' (str/lower-case (name k))
              vs (.getAll headers k')]
          (if (.isEmpty vs)
            default-value
            (if (== 1 (.size vs))
              (.get vs 0)
              (str/join "," vs))))))))

(defn headers->map [^HttpHeaders h]
  (HeaderMap. h nil nil nil))

(defn map->headers!
  "Despite the name, this doesn't convert; it adds headers from the Ring :headers
   map to the Netty HttpHeaders param."
  [^HttpHeaders h m]
  (doseq [e m]
    (let [k (normalize-header-key (key e))
          v (val e)]

      (cond
        (nil? v)
        (throw (IllegalArgumentException. (str "nil value for header key '" k "'")))

        (sequential? v)
        (.add h ^CharSequence k ^Iterable v)

        :else
        (.add h ^CharSequence k ^Object v)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP/1.1 request/response handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn ring-response->netty-response
  "Turns a Ring response into a Netty HTTP/1.1 DefaultHttpResponse"
  ^DefaultHttpResponse
  [rsp]
  (let [status (get rsp :status 200)
        headers (get rsp :headers)
        netty-rsp (DefaultHttpResponse.
                    HttpVersion/HTTP_1_1
                    (HttpResponseStatus/valueOf status)
                    false)]
    (when headers
      (map->headers! (.headers netty-rsp) headers))
    netty-rsp))

(defn ring-request->netty-request
  "Turns a Ring request into a Netty HTTP/1.1 DefaultHttpRequest"
  ^DefaultHttpRequest
  [req]
  (let [headers (get req :headers)
        netty-req (DefaultHttpRequest.
                    HttpVersion/HTTP_1_1
                    (-> req (get :request-method) name str/upper-case HttpMethod/valueOf)
                    (str (get req :uri)
                         (when-let [q (get req :query-string)]
                           (str "?" q))))]
    (when headers
      (map->headers! (.headers netty-req) headers))
    netty-req))

(defn ring-request->full-netty-request
  "Turns a Ring request into a Netty HTTP/1.1 DefaultFullHttpRequest"
  ^DefaultFullHttpRequest
  [req]
  (let [headers (get req :headers)
        netty-req (DefaultFullHttpRequest.
                    HttpVersion/HTTP_1_1
                    (-> req (get :request-method) name str/upper-case HttpMethod/valueOf)
                    (str (get req :uri)
                         (when-let [q (get req :query-string)]
                           (str "?" q)))
                    (netty/to-byte-buf (:body req)))]
    (when headers
      (map->headers! (.headers netty-req) headers))
    netty-req))

(p/def-derived-map NettyRequest
  [^HttpRequest req
   ssl?
   ^Channel ch
   ^AtomicBoolean websocket?
   question-mark-index
   body
   request-arrived]
  :uri (let [idx (long question-mark-index)]
         (if (neg? idx)
           (.uri req)
           (.substring (.uri req) 0 idx)))
  :query-string (let [uri (.uri req)]
                  (if (neg? question-mark-index)
                    nil
                    (.substring uri (unchecked-inc question-mark-index))))
  :headers (-> req .headers headers->map)
  :request-method (-> req .method .name str/lower-case keyword)
  :body body
  :scheme (if ssl? :https :http)
  :aleph/keep-alive? (HttpUtil/isKeepAlive req)
  :server-name (netty/channel-server-name ch)
  :server-port (netty/channel-server-port ch)
  :remote-addr (netty/channel-remote-address ch)
  :aleph/request-arrived request-arrived
  :protocol "HTTP/1.1")

(p/def-derived-map NettyResponse [^HttpResponse rsp complete body]
  :status (-> rsp .status .code)
  :aleph/keep-alive? (HttpUtil/isKeepAlive rsp)
  :headers (-> rsp .headers headers->map)
  ;; Terrible name. It means, did the conn end unexpectedly early?
  :aleph/complete complete
  :body body)

(defn netty-request->ring-request
  "Confusingly named, this converts a Netty HttpRequest object to a NettyRequest
   object, which looks like a Ring map."
  [^HttpRequest req ssl? ch body]
  (->NettyRequest req ssl?
   ch
   (AtomicBoolean. false)
   (-> req .uri (.indexOf (int 63)))
   body
   (System/nanoTime)))

(defn netty-response->ring-response
  [rsp complete body]
  (->NettyResponse rsp complete body))

(defn ring-request-ssl-session [^NettyRequest req]
  (netty/channel-ssl-session (.ch req)))

;;;

(defn has-content-length? [^HttpMessage msg]
  (HttpUtil/isContentLengthSet msg))

(defn try-set-content-length! [^HttpMessage msg ^long length]
  (when-not (has-content-length? msg)
    (HttpUtil/setContentLength msg length)))

(def empty-last-content LastHttpContent/EMPTY_LAST_CONTENT)


(defn send-streaming-body
  "Write out a msg and a body that's streamable"
  [ch ^HttpMessage msg body]

  (HttpUtil/setTransferEncodingChunked msg (boolean (not (has-content-length? msg))))
  (netty/write ch msg)

  (if-let [body' (if (sequential? body)

                   ;; write out all the data we have already, and return the rest
                   (let [buf (netty/allocate ch)
                         pending? (instance? clojure.lang.IPending body)]
                     (loop [s (map common/coerce-element body)]
                       (cond

                         ;; lazy and no data available yet - write out and move on
                         (and pending? (not (realized? s)))
                         (do
                           (netty/write-and-flush ch buf)
                           s)

                         ;; If we're out of data, write out the buf, and return nil
                         (empty? s)
                         (do
                           (netty/write-and-flush ch buf)
                           nil)

                         ;; if some data is ready, append it to the buf and recur
                         (or (not pending?) (realized? s))
                         (let [x (first s)]
                           (netty/append-to-buf! buf x)
                           (recur (rest s)))

                         :else
                         (do
                           (netty/write-and-flush ch buf)
                           s))))

                   (do
                     (netty/flush ch)
                     body))]

    (let [d (d/deferred)
          src (if (or (sequential? body') (s/stream? body'))
                (->> body'
                     s/->source
                     (s/transform
                      (keep
                       (fn [x]
                         (try
                           (netty/to-byte-buf x)
                           (catch Throwable e
                             (log/error "error converting" (.getName (class x)) "to ByteBuf")
                             (d/error! d e)
                             (netty/close ch)
                             nil))))))
                (netty/to-byte-buf-stream body' 8192))

          sink (netty/sink ch false #(DefaultHttpContent. %))

          ;; mustn't close over body' if NOT a stream, can hold on to data too long when conns are keep-alive
          ch-close-handler (if (s/stream? body')
                             #(s/close! body')
                             #(s/close! src))]

      (s/connect src sink)

      ;; set up close handlers
      (-> ch
          netty/channel
          .closeFuture
          netty/wrap-future
          (d/chain' (fn [_] (ch-close-handler))))

      (s/on-closed sink
                   (fn []
                     (when (instance? Closeable body)
                       (.close ^Closeable body))

                     (.execute (-> ch aleph.netty/channel .eventLoop)
                               #(d/success! d
                                            (netty/write-and-flush ch empty-last-content)))))
      d)

    (netty/write-and-flush ch empty-last-content)))



(defn send-chunked-file
  "Write out a msg and an HttpFile as a ChunkedInput"
  [ch ^HttpMessage msg ^HttpFile file]
  (let [raf (RandomAccessFile. ^File (.-fd file) "r")
        cf (ChunkedFile. raf
                         (.-offset file)
                         (.-length file)
                         (.-chunk-size file))]

    (try-set-content-length! msg (.-length file))
    (netty/write ch msg)
    (netty/write-and-flush ch (HttpChunkedInput. cf))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [ch ^HttpMessage msg ^ChunkedInput body]
  (netty/write ch msg)
  (netty/write-and-flush ch body))

(defn send-file-region
  "Write out a msg and an HttpFile as a FileRegion.

   NB: incompatible with SslHandler."
  [ch ^HttpMessage msg ^HttpFile file]
  (let [raf (RandomAccessFile. ^File (.-fd file) "r")
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc ^long (.-offset file) ^long (.-length file))]
    (try-set-content-length! msg (.-length file))
    (netty/write ch msg)
    (netty/write ch fr)
    (netty/write-and-flush ch empty-last-content)))

;; TODO: Try to enable ChunkedInput with SSL; SSL can't use FileRegion,
;;   which calls .transferTo(), but anything resulting in ByteBufs should be fine,
;;   so ChunkedINputs should work, too.
(defn send-file-body
  "Writes out a msg and chooses how to write out a body.

   If using SSL, sends a streaming body.
   If the ChunkedWriteHandler is on the pipeline, sends as a ChunkedInput.
   Otherwise, sends as a FileRegion."
  [ch ssl? ^HttpMessage msg ^HttpFile file]
  (cond
    ssl?
    (let [body (when (pos-int? (.length file)) (file/http-file->stream file))]
      (send-streaming-body ch msg body))

    (some? (-> ch netty/channel .pipeline (.get ChunkedWriteHandler)))
    (send-chunked-file ch msg file)

    :else
    (send-file-region ch msg file)))

(defn send-contiguous-body
  "Writes out a msg and a body that can be turned into a single ByteBuf.

   Sets the content-length header on requests, and non-204/non-1xx responses."
  [ch ^HttpMessage msg body]
  (log/debug "send-contiguous-body headers:" (.headers msg))
  (let [omitted? (identical? :aleph/omitted body)
        body (if (or (nil? body) omitted?)
               empty-last-content
               (DefaultLastHttpContent. (netty/to-byte-buf ch body)))
        length (-> ^HttpContent body .content .readableBytes)]

    (when-not omitted?
      (if (instance? HttpResponse msg)
        (let [code (-> ^HttpResponse msg .status .code)]
          (when-not (or (<= 100 code 199) (= 204 code))
            (try-set-content-length! msg length)))
        (try-set-content-length! msg length)))

    (netty/write ch msg)
    (netty/write-and-flush ch body)))

(let [ary-class (class (byte-array 0))

      ;; extracted to make `send-message` more inlineable
      handle-cleanup
      (fn [ch f]
        (-> f
            (d/chain'
              (fn [^ChannelFuture f]
                (if f
                  (.addListener f ChannelFutureListener/CLOSE)
                  (netty/close ch))))
            (d/catch' (fn [_]))))]

  (defn send-message
    "Write an HttpMessage and body to a Netty channel or context. Returns a
     ChannelFuture for the status of the write/flush operations.

     Accepts Strings, ByteBuffers, ByteBufs, byte[], ChunkedInputs,
     Files, Paths, HttpFiles, seqs and streams for bodies.

     Seqs and streams must be, or be coercible to, a stream of ByteBufs."
    [ch keep-alive? ssl? ^HttpMessage msg body]

    (let [fut (cond
                (or
                  (nil? body)
                  (identical? :aleph/omitted body)
                  (instance? String body)
                  (instance? ary-class body)
                  (instance? ByteBuffer body)
                  (instance? ByteBuf body))
                (send-contiguous-body ch msg body)

                (instance? ChunkedInput body)
                (send-chunked-body ch msg body)

                (instance? File body)
                (send-file-body ch ssl? msg (file/http-file body))

                (instance? Path body)
                (send-file-body ch ssl? msg (file/http-file body))

                (instance? HttpFile body)
                (send-file-body ch ssl? msg body)

                :else
                (let [class-name (StringUtil/simpleClassName body)]
                  (try
                    (send-streaming-body ch msg body)
                    (catch Throwable e
                      (log/error e "error sending body of type " class-name)
                      (throw e)))))]

      (when-not keep-alive?
        (handle-cleanup ch fut))

      fut)))

(defn send-response-decoder-failure [^ChannelHandlerContext ctx msg response-stream]
  (let [^Throwable ex (common/decoder-failure msg)]
    (s/put! response-stream ex)
    (netty/close ctx)))

(defn handle-decoder-failure [^ChannelHandlerContext ctx msg stream complete response-stream]
  (if (instance? HttpContent msg)
    ;; note that we are most likely to get this when dealing
    ;; with transfer encoding chunked
    (if-let [s @stream]
      (do
        ;; flag that connection was terminated early
        (d/success! @complete true)
        (s/close! s))
      (send-response-decoder-failure ctx msg response-stream))
    (send-response-decoder-failure ctx msg response-stream)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; backwards compatibility
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:deprecated ^:no-doc attach-idle-handlers netty/add-idle-handlers)
(def ^:deprecated ^:no-doc close-on-idle-handler netty/close-on-idle-handler)
(def ^:deprecated ^:no-doc coerce-element common/coerce-element)


(comment
  (let [ch (EmbeddedChannel.)
        keep-alive? true
        ssl? true
        body (-> ch .alloc (.buffer 100))
        msg :fixme]
    (quick-bench
      (send-message ch keep-alive? ssl? msg body)))
  )
