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
    (io.netty.handler.timeout
      IdleState
      IdleStateEvent
      IdleStateHandler)
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

(def aleph-server-header "Aleph value for the Server header" (AsciiString. "Aleph/0.7.0-alpha1"))

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


(defn close-on-idle-handler []
  (netty/channel-handler
    :user-event-triggered
    ([_ ctx evt]
     (if (and (instance? IdleStateEvent evt)
              (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
       (netty/close ctx)
       (.fireUserEventTriggered ctx evt)))))

(defn add-idle-handlers
  ^ChannelPipeline
  [^ChannelPipeline pipeline idle-timeout]
  (if (pos? idle-timeout)
    (doto pipeline
          (.addLast "idle" ^ChannelHandler (IdleStateHandler. 0 0 idle-timeout TimeUnit/MILLISECONDS))
          (.addLast "idle-close" ^ChannelHandler (close-on-idle-handler)))
    pipeline))

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

