(ns ^:no-doc aleph.http.common
  "Code shared across both client/server and different HTTP versions"
  (:require
    [aleph.netty :as netty]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (io.netty.buffer ByteBuf)
    (io.netty.channel
      ChannelHandler
      ChannelPipeline)
    (io.netty.handler.codec
      DateFormatter
      DecoderResult
      DecoderResultProvider)
    (io.netty.handler.ssl ApplicationProtocolConfig ApplicationProtocolNames SslContext)
    (io.netty.util AsciiString)
    (io.netty.util.concurrent
      EventExecutorGroup
      FastThreadLocal)
    (io.netty.util.internal StringUtil)
    (java.nio ByteBuffer)
    (java.util Date)
    (java.util.concurrent TimeUnit)
    (java.util.concurrent.atomic AtomicReference)))

(set! *warn-on-reflection* true)

(def aleph-server-header "Aleph value for the Server header" (AsciiString. "Aleph/0.8.3"))

(defprotocol HeaderMap
  (get-header-values [m ^String k]))

(defn coerce-element
  "Turns an object into something writable to a Netty channel.

   Byte-based data types are untouched, as are strings. Everything else is
   converted to a string."
  [x]
  (if (or
        (instance? String x)
        (instance? netty/byte-array-class x)
        (instance? ByteBuffer x)
        (instance? ByteBuf x))
    x
    (str x)))

(defn body-byte-buf-stream
  "Turns the body into a byte-buf stream.

   NB: chunk-size is only used if the body is converted by byte-streams,
   i.e., not a stream or sequence."
  [d ch body chunk-size]
  (if (or (sequential? body) (s/stream? body))
    (->> body
         s/->source
         (s/transform
           (keep
             (fn [x]
               (try
                 (netty/to-byte-buf x)
                 (catch Throwable e
                   (log/error (str "Error converting " (StringUtil/simpleClassName x) " to ByteBuf"))
                   (d/error! d e)
                   (netty/close ch)
                   nil))))))
    (netty/to-byte-buf-stream body chunk-size)))


(defn add-non-http-handlers
  "Set up the pipeline with HTTP-independent handlers.

   Includes logger, proxy, and custom pipeline-transform handlers."
  [^ChannelPipeline p logger pipeline-transform]
  (when (some? logger)
    (log/trace "Adding activity logger")
    (.addFirst p "activity-logger" ^ChannelHandler logger)

    (when (log/enabled? :debug)
      (.addLast p
                "debug"
                ^ChannelHandler
                (netty/channel-inbound-handler
                  :channel-read ([_ ctx msg]
                                 (log/debug "received msg of class" (StringUtil/simpleClassName ^Object msg))
                                 (log/debug "msg:" msg))))))

  (pipeline-transform p)
  p)

(defn add-exception-handler
  "Set up the pipeline with an exception handler. Takes an optional name and
   handler, which will be passed (1) the exception and (2) the context. By
   default, it just logs the error, and lets Netty handle it.

   NB: This is for the *final* handler in a pipeline. Any supplied ex-handler gets
   full control. Ring is not involved; if you wish to send something, use Netty.
   If you want the channel closed, you must do it. If you wish to forward the
   error on, call .fireExceptionCaught() in your ex-handler."
  ([^ChannelPipeline p]
   (add-exception-handler p "ex-handler"))
  ([^ChannelPipeline p ^String handler-name]
   (add-exception-handler p handler-name nil))
  ([^ChannelPipeline p ^String handler-name ex-handler]
   (.addLast p
             handler-name
             ^ChannelHandler
             (netty/channel-inbound-handler
               {:exception-caught
                ([_ ctx ex]
                 (log/error ex (str "Exception in channel (" handler-name ")."))
                 (if ex-handler
                   (ex-handler ex ctx)
                   (.fireExceptionCaught ctx ex)))}))))

(defn ssl-ctx-supports-http2?
  "Does this SslContext have an ALPN that supports HTTP/2?"
  [^SslContext ssl-context]
  (some-> ssl-context
          (.applicationProtocolNegotiator)
          (.protocols)
          (.contains ApplicationProtocolNames/HTTP_2)))

(def supported-http-versions #{:http1 :http2})
(def supported-http-protocol-names (into #{}
                                         (map netty/->application-protocol-name)
                                         supported-http-versions))

(defn- assert-consistent-alpn-config! [alpn-protocols desired-http-versions]
  (let [desired-http-protocols (map netty/->application-protocol-name desired-http-versions)
        alpn-http-protocols (filter supported-http-protocol-names alpn-protocols)]
    (when-not (= desired-http-protocols alpn-http-protocols)
      (let [emsg (if-let [missing-http-protocols (seq (remove (set alpn-http-protocols) desired-http-protocols))]
                   "Some desired HTTP versions are not part of ALPN config."
                   (if (= (count alpn-http-protocols) (count desired-http-protocols))
                     "Desired HTTP version preference order differs from ALPN config."
                     "ALPN config contains more HTTP versions than desired."))]
        (throw (ex-info emsg
                        {:desired-http-versions desired-http-versions
                         :alpn-protocols alpn-protocols}))))))

(defn ensure-consistent-alpn-config
  "Ensures that `ssl-context` has an ALPN config that matches the `desired-http-versions`.

   `desired-http-versions` is a sequence of keywords from `supported-http-versions`.

   `ssl-context` is either an SSL context options map (see `aleph.netty/ssl-client-context` and
   `aleph.netty/ssl-server-context`) or an `io.netty.handler.ssl.SslContext` instance.

   Returns `ssl-context` (potentially updated, see below).

   If `ssl-context` is nil, returns nil.

   If `ssl-context` is an options map and it contains no `:application-protocol-config` key, a
   matching `:application-protocol-config` is added (based on `desired-http-versions`)

   If `ssl-context` is an options map and it does contain an `:application-protocol-config` key, its
   supported protocols are validated to match `desired-http-versions`.

   Otherwise, if `ssl-context` is an `io.netty.handler.ssl.SslContext` instance, its ALPN config's
   protocols are validated to match `desired-http-versions`. If it doesn't have an ALPN config,
   `desired-http-versions` may only contain `:http1`."
  [ssl-context desired-http-versions]
  (when-let [unsupported-http-versions (seq (remove supported-http-versions desired-http-versions))]
    (throw (ex-info "Unsupported desired HTTP versions"
                    {:unsupported-http-versions unsupported-http-versions})))
  (cond
    (map? ssl-context)
    (if-let [apc ^ApplicationProtocolConfig (:application-protocol-config ssl-context)]
      (do
        (assert-consistent-alpn-config! (.supportedProtocols apc) desired-http-versions)
        ssl-context)
      (assoc ssl-context
             :application-protocol-config
             (netty/application-protocol-config desired-http-versions)))

    (nil? ssl-context)
    nil

    :else
    ;; NOTE: `SslContext` always has an `ApplicationProtocolNegotiator`, even if
    ;; `.applicationProtocolConfig` was never invoked in the `SslContextBuilder`. In this case, its
    ;; `.protocols` will be an empty collection, which we thus treat as if no ALPN config is
    ;; present.
    (if-let [protocols (some-> ^SslContext ssl-context
                               (.applicationProtocolNegotiator)
                               (.protocols)
                               (seq))]
      (do
        (assert-consistent-alpn-config!
         protocols
         desired-http-versions)
        ssl-context)
      (if (= [:http1] desired-http-versions)
        ssl-context
        (throw (ex-info "Supplied SslContext with no ALPN config, but requested secure non-HTTP/1 versions that require ALPN."
                        {:desired-http-versions desired-http-versions}))))))

(defn validate-http1-pipeline-transform
  "Checks that :pipeline-transform is not being used with HTTP/2, since Netty
   H2 code uses multiple pipelines instead of one.

   If both :pipeline-transform and :http1-pipeline-transform are set, prefers
   :http1-pipeline-transform.

   Returns the chosen transform fn."
  [{:keys [ssl-context
           use-h2c?
           pipeline-transform
           http1-pipeline-transform]}]
  ;; throw when using http2 and :pipeline-transform
  (when (and (or use-h2c? (ssl-ctx-supports-http2? ssl-context))
             (not (contains? #{nil identity} pipeline-transform)))
    (throw (IllegalArgumentException.
             "Cannot use :pipeline-transform with HTTP/2. If this is intended to be HTTP/1-only, please use :http1-pipeline-transform. If you need to adjust the pipeline for HTTP/2, please see :http2-conn-pipeline-transform and :http2-stream-pipeline-transform.")))

  (cond
    (and (contains? #{nil identity} http1-pipeline-transform)
         (contains? #{nil identity} pipeline-transform))
    identity

    (contains? #{nil identity} http1-pipeline-transform)
    pipeline-transform

    :else
    http1-pipeline-transform))

(defn ring-error-response
  "Generic 500 error Ring response"
  [^Throwable e]
  (log/error e "Error in HTTP handler")
  {:status  500
   :headers {"content-type" "text/plain"}
   :body    "Internal Server Error"})

(defn decoder-failed? [^DecoderResultProvider msg]
  (.isFailure ^DecoderResult (.decoderResult msg)))


(defn ^Throwable decoder-failure [^DecoderResultProvider msg]
  (.cause ^DecoderResult (.decoderResult msg)))

(defn invalid-value-exception
  [req x]
  (IllegalArgumentException.
    (str "Cannot treat "
         (pr-str x)
         (when (some? x) (str " of " (type x)))
         (format " as a response to '%s'.
Ring response map expected.

Example: {:status 200
          :body \"hello world\"
          :headers \"text/plain\"}"
                 (pr-str (select-keys req [:uri :request-method :query-string :headers]))))))


;; Date-supporting fns
(defonce ^FastThreadLocal date-value (FastThreadLocal.))

(defn rfc-1123-date-string
  "Returns an RFC 1123 date string, e.g. \"Sat, 01 Jul 2023 09:49:56 GMT\""
  ^String
  []
  (DateFormatter/format (Date.)))

(defn date-header-value
  "Returns a cached RFC 1123 date string. The ThreadLocal cached value is
   updated every second."
  ^CharSequence
  [^EventExecutorGroup exec]
  (if-let [^AtomicReference ref (.get date-value)]
    (.get ref)
    (let [ref (AtomicReference. (AsciiString. (rfc-1123-date-string)))]
      (.set date-value ref)
      (.scheduleAtFixedRate exec
                            #(.set ref (AsciiString. (rfc-1123-date-string)))
                            1000
                            1000
                            TimeUnit/MILLISECONDS)
      (.get ref))))

