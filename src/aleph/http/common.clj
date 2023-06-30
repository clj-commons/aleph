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
      DecoderResult
      DecoderResultProvider)
    (io.netty.handler.timeout
      IdleState
      IdleStateEvent
      IdleStateHandler)
    (io.netty.util.internal StringUtil)
    (java.nio ByteBuffer)
    (java.util.concurrent TimeUnit)))

(set! *warn-on-reflection* true)

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

(defn attach-idle-handlers
  ^ChannelPipeline
  [^ChannelPipeline pipeline idle-timeout]
  (if (pos? idle-timeout)
    (doto pipeline
          (.addLast "idle" ^ChannelHandler (IdleStateHandler. 0 0 idle-timeout TimeUnit/MILLISECONDS))
          (.addLast "idle-close" ^ChannelHandler (close-on-idle-handler)))
    pipeline))


(defn decoder-failed? [^DecoderResultProvider msg]
  (.isFailure ^DecoderResult (.decoderResult msg)))


(defn ^Throwable decoder-failure [^DecoderResultProvider msg]
  (.cause ^DecoderResult (.decoderResult msg)))
