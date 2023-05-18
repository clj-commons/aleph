(ns ^:no-doc aleph.http.common
  "Code shared across both client/server and different HTTP versions"
  (:require
    [aleph.netty :as netty])
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
    (java.nio ByteBuffer)
    (java.util.concurrent TimeUnit)))

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
