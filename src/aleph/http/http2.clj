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
      Http2Exception
      Http2Headers
      Http2StreamChannel)
    (io.netty.handler.stream ChunkedInput)
    (io.netty.util.internal StringUtil)
    (java.io File)
    (java.nio ByteBuffer)
    (java.nio.file Path)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (class (byte-array 0)))

(defn- ring-map->netty-http2-headers
  "Turns a Ring map's headers into a Netty Http2Headers object."
  ^DefaultHttp2Headers
  [req]
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
      (run! (fn [entry]
              (let [k (str/lower-case (key entry))          ; http2 requires lowercase headers
                    v (val entry)]
                (cond
                  (nil? v)
                  (throw (IllegalArgumentException. (str "nil value for header key '" k "'")))

                  (sequential? v)
                  (.add h2-headers ^CharSequence k ^Iterable v)

                  :else
                  (.add h2-headers ^CharSequence k ^Object v))))
            headers))
    h2-headers))

(defn- try-set-content-length!
  "Attempts to set the content-length header if not already set.

   Will skip if it's a response and the status code is 1xx or 204.
   Negative length values are ignored."
  ^Http2Headers
  [^Http2Headers headers ^long length]
  (when-not (.get headers "content-length")
    (.setLong headers "content-length" length)))


(defn send-contiguous-body [ch ^Http2Headers headers body]
  (let [body-bb (netty/to-byte-buf ch body)
        body-frame (DefaultHttp2DataFrame. body-bb true)
        length (.readableBytes body-bb)]

    (if-let [code (Long/parseLong (.status headers))]
      (when-not (or (<= 100 code 199)
                    (= 204 code))
        (try-set-content-length! headers length))
      (try-set-content-length! headers length))

    (netty/write ch (DefaultHttp2HeadersFrame. headers))
    (netty/write-and-flush ch body-frame)))



(defn- send-message
  [ch msg body]
  (println "send-message fired")
  (try
    (let [headers (ring-map->netty-http2-headers msg)]
      (prn "headers" headers) (flush)

      (cond
        (or (nil? body)
            (identical? :aleph/omitted body))
        (do
          (println "body nil or omitted") (flush)
          (let [header-frame (DefaultHttp2HeadersFrame. headers true)]
            (netty/write-and-flush ch header-frame)))

        (or
          (instance? String body)
          (instance? byte-array-class body)
          (instance? ByteBuffer body)
          (instance? ByteBuf body))
        (send-contiguous-body ch headers body)

        (instance? ChunkedInput body)
        (do
          (let [emsg (str "ChunkedInput not supported yet")
                e (Http2Exception. emsg)]
            (println emsg)
            (log/error e emsg)
            (netty/close ch)))

        (instance? File body)
        (do
          (let [emsg (str "File not supported yet")
                e (Http2Exception. emsg)]
            (println emsg)
            (log/error e emsg)
            (netty/close ch)))

        (instance? Path body)
        (do
          (let [emsg (str "Path not supported yet")
                e (Http2Exception. emsg)]
            (println emsg)
            (log/error e emsg)
            (netty/close ch)))

        (instance? HttpFile body)
        (do
          (let [emsg (str "HttpFile not supported yet")
                e (Http2Exception. emsg)]
            (println emsg)
            (log/error e emsg)
            (netty/close ch)))

        (instance? FileRegion body)
        (do
          (let [emsg (str "FileRegion not supported yet")
                e (Http2Exception. emsg)]
            (println emsg)
            (log/error e emsg)
            (netty/close ch)))

        #_#_:else
                (let [class-name (.getName (class body))]
                  (try
                    (send-streaming-body ch msg body)
                    (catch Throwable e
                      (log/error e "error sending body of type " class-name)
                      (throw e))))))

    (catch Exception e
      (println "error sending message" e)
      (log/error e "error sending message")
      (throw (ex-info "error sending message" {:msg msg :body body} e)))))


(defn req-preprocess
  [ch req responses]
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
                          (netty/close ch)
                          (s/put! responses (d/error-deferred (Http2Exception. "HTTP/2 multipart not supported yet"))))
                        #_(multipart/encode-request req' parts))]

      #_#_
              (when-let [save-message (get req :aleph/save-request-message)]
                ;; debug purpose only
                ;; note, that req' is effectively mutable, so
                ;; it will "capture" all changes made during "send-message"
                ;; execution
                (reset! save-message req'))

              (when-let [save-body (get req :aleph/save-request-body)]
                ;; might be different in case we use :multipart
                (reset! save-body body))

      (-> (netty/safe-execute ch
                              (send-message ch req' body))
          (d/catch' (fn [e]
                      (s/put! responses (d/error-deferred e))
                      (netty/close ch)))))

    ;; this will usually happen because of a malformed request
    (catch Throwable e
      (s/put! responses (d/error-deferred e))
      (netty/close ch))))
