(ns aleph.http.websocket.common
  (:require [aleph.netty :as netty]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as d]
            [manifold.stream :as s])
  (:import (io.netty.channel Channel ChannelHandler ChannelHandlerContext ChannelPipeline)
           (io.netty.handler.codec.http.websocketx
             BinaryWebSocketFrame
             CloseWebSocketFrame
             PingWebSocketFrame
             TextWebSocketFrame
             WebSocketFrame)
           (io.netty.handler.stream ChunkedInput)
           (io.netty.handler.timeout IdleStateHandler)
           (java.util.concurrent
             ConcurrentLinkedQueue
             TimeUnit)))

(def close-empty-status-code -1)

(deftype WebsocketPing [deferred payload])
(deftype WebsocketClose [deferred status-code reason-text])


(defn resolve-pings!
  [^ConcurrentLinkedQueue pending-pings v]
  (loop []
    (when-let [^WebsocketPing ping (.poll pending-pings)]
      (let [d' (.-deferred ping)]
        (when (not (realized? d'))
          (try
            (d/success! d' v)
            (catch Throwable e
              (log/error e "error in ping callback")))))
      (recur))))

(defn websocket-message-coerce-fn
  ([ch pending-pings]
   (websocket-message-coerce-fn ch pending-pings nil))
  ([^Channel ch ^ConcurrentLinkedQueue pending-pings close-handshake-fn]
   (fn [msg]
     (condp instance? msg
       WebSocketFrame
       msg

       ChunkedInput
       msg

       WebsocketPing
       (let [^WebsocketPing msg msg
             ;; this check should be safe as we rely on the strictly sequential
             ;; processing of all messages put onto the same stream
             send-ping? (.isEmpty pending-pings)]
         (.offer pending-pings msg)
         (when send-ping?
           (if-some [payload (.-payload msg)]
             (->> payload
                  (netty/to-byte-buf ch)
                  (PingWebSocketFrame.))
             (PingWebSocketFrame.))))

       WebsocketClose
       (when (some? close-handshake-fn)
         (let [^WebsocketClose msg msg
               code (.-status-code msg)
               frame (if (identical? close-empty-status-code code)
                       (CloseWebSocketFrame.)
                       (CloseWebSocketFrame. ^int code
                                             ^String (.-reason-text msg)))
               succeed? (close-handshake-fn frame)]
           ;; it still feels somewhat clumsy to make concurrent
           ;; updates and realized deferred from internals of the
           ;; function that meant to be a stateless coercer
           (when-not (d/realized? (.-deferred msg))
             (d/success! (.-deferred msg) succeed?))

           ;; we want to close the sink here to stop accepting
           ;; new messages from the user
           (when succeed?
             netty/sink-close-marker)))

       CharSequence
       (TextWebSocketFrame. (bs/to-string msg))

       (BinaryWebSocketFrame. (netty/to-byte-buf ch msg))))))

(defn websocket-ping
  [conn d' data]
  (d/chain'
    (s/put! conn (WebsocketPing. d' data))
    #(when (and (false? %) (not (d/realized? d')))
       ;; meaning connection is already closed
       (d/success! d' false)))
  d')


(defn websocket-close!
  [conn status-code reason-text d']
  (when-not (or (identical? close-empty-status-code status-code)
                (<= 1000 status-code 4999))
    (throw (IllegalArgumentException.
             "websocket status code should be in range 1000-4999")))

  (let [payload (WebsocketClose. d' status-code reason-text)]
    (d/chain'
      (s/put! conn payload)
      (fn [put?]
        (when (and (false? put?) (not (d/realized? d')))
          ;; if the stream does not accept new messages,
          ;; connection is already closed
          (d/success! d' false))))
    d'))

(defn attach-heartbeats-handler
  [^ChannelPipeline pipeline heartbeats]
  (when (and (some? heartbeats)
             (pos? (:send-after-idle heartbeats)))
    (let [after (:send-after-idle heartbeats)]
      (.addLast pipeline
                "websocket-heartbeats"
                ^ChannelHandler
                (IdleStateHandler. 0 0 after TimeUnit/MILLISECONDS)))))


(defn handle-heartbeat
  [^ChannelHandlerContext ctx conn {:keys [payload timeout]}]
  (let [done (d/deferred)]
    (websocket-ping conn done payload)
    (when (and timeout (pos? timeout))
      (-> done
          (d/timeout! timeout ::ping-timeout)
          (d/chain'
            (fn [v]
              (when (and (identical? ::ping-timeout v)
                         (.isOpen ^Channel (.channel ctx)))
                (netty/close ctx))))))))
