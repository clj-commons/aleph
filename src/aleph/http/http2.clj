(ns ^:no-doc aleph.http.http2
  (:require
    [aleph.netty :as netty]
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
      Http2FrameStreamException
      Http2Headers
      Http2StreamChannel)
    (io.netty.handler.stream ChunkedInput)
    (io.netty.util.internal StringUtil)
    (java.io File)
    (java.nio ByteBuffer)
    (java.nio.file Path)))

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
  [^Http2StreamChannel ch ^Http2Headers headers body]
  (let [body-bb (netty/to-byte-buf ch body)
        stream (.stream ch)]
    (try-set-content-length! headers (.readableBytes body-bb))

    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch (-> body-bb (DefaultHttp2DataFrame. true) (.stream stream)))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [^Http2StreamChannel ch ^Http2Headers headers ^ChunkedInput body]
  (let [len (.length body)]
    (when (p/>= len 0)
      (try-set-content-length! headers len)))

  (netty/write ch (DefaultHttp2HeadersFrame. headers))
  (netty/write-and-flush ch (Http2DataChunkedInput. body (.stream ch))))

(defn- send-message
  [^Http2StreamChannel ch ^Http2Headers headers body]
  (println "http2 send-message fired")
  (try
    (cond
      (or (nil? body)
          (identical? :aleph/omitted body))
      (do
        (println "body nil or omitted") (flush)
        ;; FIXME: this probably breaks with the Http2 to Http1 codec in the pipeline
        (let [header-frame (DefaultHttp2HeadersFrame. headers true)]
          (prn header-frame)
          (netty/write-and-flush ch header-frame)))

      (or
        (instance? String body)
        (instance? byte-array-class body)
        (instance? ByteBuffer body)
        (instance? ByteBuf body))
      (send-contiguous-body ch headers body)

      (instance? ChunkedInput body)
      (send-chunked-body ch headers body)

      (instance? File body)
      (do
        (let [emsg (str "File not supported yet")
              e (Http2FrameStreamException. (.stream ch) Http2Error/PROTOCOL_ERROR emsg)]
          (println emsg)
          (log/error e emsg)
          (netty/close ch)))

      (instance? Path body)
      (do
        (let [emsg (str "Path not supported yet")
              e (Http2FrameStreamException. (.stream ch) Http2Error/PROTOCOL_ERROR emsg)]
          (println emsg)
          (log/error e emsg)
          (netty/close ch)))

      (instance? HttpFile body)
      (do
        (let [emsg (str "HttpFile not supported yet")
              e (Http2FrameStreamException. (.stream ch) Http2Error/PROTOCOL_ERROR emsg)]
          (println emsg)
          (log/error e emsg)
          (netty/close ch)))

      (instance? FileRegion body)
      (do
        (let [emsg (str "FileRegion not supported yet")
              e (Http2FrameStreamException. (.stream ch) Http2Error/PROTOCOL_ERROR emsg)]
          (println emsg)
          (log/error e emsg)
          (netty/close ch)))

      #_#_:else
              (let [class-name (.getName (class body))]
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
                                         (ex-info "Error sending message" {:headers headers :body body} e))))))

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
                      (println "Error in req-preprocess" e) (flush)
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
    (require '[aleph.http.client])
    (import java.net.InetSocketAddress))

  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress/createUnresolved "www.google.com" (int 443))
                 true
                 {:on-closed #(println "http conn closed")}))

    @(conn {:request-method :get}))
  )
