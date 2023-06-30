(ns aleph.http.websocket.server
  (:require
    [aleph.http.core :as http]
    [aleph.http.websocket.common :as ws.common]
    [aleph.netty :as netty]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    ;; Do not remove
    (aleph.http.core
      NettyRequest)
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelPipeline)
    (io.netty.handler.codec.http
      DefaultHttpHeaders
      HttpContentCompressor
      HttpRequest)
    (io.netty.handler.codec.http.websocketx
      WebSocketServerHandshakerFactory
      WebSocketServerHandshaker
      PingWebSocketFrame
      PongWebSocketFrame
      TextWebSocketFrame
      BinaryWebSocketFrame
      CloseWebSocketFrame
      WebSocketFrameAggregator)
    (io.netty.handler.codec.http.websocketx.extensions.compression
      WebSocketServerCompressionHandler)
    (io.netty.handler.stream
      ChunkedWriteHandler)
    (io.netty.handler.timeout
      IdleState
      IdleStateEvent)
    (java.io
      IOException)
    (java.util.concurrent
      ConcurrentLinkedQueue)
    (java.util.concurrent.atomic
      AtomicBoolean)))


(defn websocket-server-handler
  ([raw-stream? ch handshaker]
   (websocket-server-handler raw-stream? ch handshaker nil))
  ([raw-stream?
    ^Channel ch
    ^WebSocketServerHandshaker handshaker
    heartbeats]
   (let [d (d/deferred)
         ^ConcurrentLinkedQueue pending-pings (ConcurrentLinkedQueue.)
         closing? (AtomicBoolean. false)
         coerce-fn (ws.common/websocket-message-coerce-fn
                     ch
                     pending-pings
                     (fn [^CloseWebSocketFrame frame]
                       (if-not (.compareAndSet closing? false true)
                         false
                         (do
                           (.close handshaker ch frame)
                           true))))
         description (fn [] {:websocket-selected-subprotocol (.selectedSubprotocol handshaker)})
         out (netty/sink ch false coerce-fn description)
         in (netty/buffered-source ch (constantly 1) 16)]

     (s/on-closed out #(ws.common/resolve-pings! pending-pings false))

     (s/on-drained
       in
       ;; there's a chance that the connection was closed by the client,
       ;; in that case *out* would be closed earlier and the underlying
       ;; netty channel is already terminated
       #(when (and (.isOpen ch)
                   (.compareAndSet closing? false true))
          (.close handshaker ch (CloseWebSocketFrame.))))

     (let [s (doto
               (s/splice out in)
               (reset-meta! {:aleph/channel ch}))]
       [s

        (netty/channel-inbound-handler

          :exception-caught
          ([_ ctx ex]
           (when-not (instance? IOException ex)
             (log/warn ex "error in websocket handler"))
           (s/close! out)
           (netty/close ctx))

          :channel-inactive
          ([_ ctx]
           (s/close! out)
           (s/close! in)
           (.fireChannelInactive ctx))

          :user-event-triggered
          ([_ ctx evt]
           (if (and (instance? IdleStateEvent evt)
                    (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
             (ws.common/handle-heartbeat ctx s heartbeats)
             (.fireUserEventTriggered ctx evt)))

          :channel-read
          ([_ ctx msg]
           (let [ch (.channel ctx)]
             (cond
               (instance? TextWebSocketFrame msg)
               (if raw-stream?
                 (let [body (.content ^TextWebSocketFrame msg)]
                   ;; pass ByteBuf body directly to next level (it's
                   ;; their reponsibility to release)
                   (netty/put! ch in body))
                 (let [text (.text ^TextWebSocketFrame msg)]
                   ;; working with text now, so we do not need
                   ;; ByteBuf inside TextWebSocketFrame
                   ;; note, that all *WebSocketFrame classes are
                   ;; subclasses of DefaultByteBufHolder, meaning
                   ;; there's no difference between releasing
                   ;; frame & frame's content
                   (netty/release msg)
                   (netty/put! ch in text)))

               (instance? BinaryWebSocketFrame msg)
               (let [body (.content ^BinaryWebSocketFrame msg)]
                 (netty/put! ch in
                             (if raw-stream?
                               body
                               ;; copied data into byte array, deallocating ByteBuf
                               (netty/release-buf->array body))))

               (instance? PingWebSocketFrame msg)
               (let [body (.content ^PingWebSocketFrame msg)]
                 ;; reusing the same buffer
                 ;; will be deallocated by Netty
                 (netty/write-and-flush ch (PongWebSocketFrame. body)))

               (instance? PongWebSocketFrame msg)
               (do
                 (netty/release msg)
                 (ws.common/resolve-pings! pending-pings true))

               (instance? CloseWebSocketFrame msg)
               (if-not (.compareAndSet closing? false true)
                 ;; closing already, nothing else could be done
                 (netty/release msg)
                 ;; reusing the same buffer
                 ;; will be deallocated by Netty
                 (.close handshaker ch ^CloseWebSocketFrame msg))

               :else
               ;; no need to release buffer when passing to a next handler
               (.fireChannelRead ctx msg)))))]))))


;; note, as we set `keep-alive?` to `false`, `send-message` will close the connection
;; after writes are done, which is exactly what we expect to happen
(defn send-websocket-request-expected!
  [ch ssl?]
  (http/send-message
    ch
    false
    ssl?
    (http/ring-response->netty-response
      {:status  400
       :headers {"content-type" "text/plain"}})
    "expected websocket request"))

(defn websocket-upgrade-request?
  "Returns `true` if given request is an attempt to upgrade to websockets"
  [^NettyRequest req]
  (let [headers (:headers req)
        conn (get headers :connection)
        upgrade (get headers :upgrade)]
    (and (contains? (when (some? conn)
                      (set (map str/trim
                                (-> (str/lower-case conn) (str/split #",")))))
                    "upgrade")
         (= "websocket" (when (some? upgrade) (str/lower-case upgrade))))))

(defn initialize-websocket-handler
  [^NettyRequest req
   {:keys [raw-stream?
           headers
           max-frame-payload
           max-frame-size
           allow-extensions?
           compression?
           pipeline-transform
           heartbeats]
    :or   {raw-stream?       false
           max-frame-payload 65536
           max-frame-size    1048576
           allow-extensions? false
           compression?      false}
    :as   options}]

  (when (and (true? (:compression? options))
             (false? (:allow-extensions? options)))
    (throw (IllegalArgumentException.
             "Per-message deflate requires extensions to be allowed")))

  (-> req ^AtomicBoolean (.websocket?) (.set true))

  (let [^Channel ch (.ch req)
        ssl? (identical? :https (:scheme req))
        url (str
              (if ssl? "wss://" "ws://")
              (get-in req [:headers "host"])
              (:uri req))
        req (http/ring-request->full-netty-request req)
        factory (WebSocketServerHandshakerFactory.
                  url
                  nil
                  (or allow-extensions? compression?)
                  max-frame-payload)]
    (try
      (if-let [handshaker (.newHandshaker factory req)]
        (try
          (let [[s ^ChannelHandler handler] (websocket-server-handler raw-stream?
                                                                      ch
                                                                      handshaker
                                                                      heartbeats)
                p (.newPromise ch)
                h (doto (DefaultHttpHeaders.) (http/map->headers! headers))
                ^ChannelPipeline pipeline (.pipeline ch)]
            ;; actually, we're not going to except anything but websocket, so...
            (doto pipeline
                  (.remove "request-handler")
                  (.remove "continue-handler")
                  (netty/remove-if-present HttpContentCompressor)
                  (netty/remove-if-present ChunkedWriteHandler)
                  (.addLast "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
                  (ws.common/attach-heartbeats-handler heartbeats)
                  (.addLast "websocket-handler" handler))
            (when compression?
              ;; Hack:
              ;; WebSocketServerCompressionHandler is stateful and requires
              ;; HTTP request to be send through the pipeline
              ;; See more:
              ;; * https://github.com/clj-commons/aleph/issues/494
              ;; * https://github.com/netty/netty/pull/8973
              (let [compression-handler (WebSocketServerCompressionHandler.)
                    ctx (.context pipeline "websocket-frame-aggregator")]
                (.addAfter pipeline
                           "websocket-frame-aggregator"
                           "websocket-deflater"
                           compression-handler)
                (.fireChannelRead ctx req)))
            (-> (try
                  (netty/wrap-future (.handshake handshaker ch ^HttpRequest req h p))
                  (catch Throwable e
                    (d/error-deferred e)))
                (d/chain'
                  (fn [_]
                    (when (some? pipeline-transform)
                      (pipeline-transform (.pipeline ch)))
                    s))
                (d/catch'
                  (fn [e]
                    (send-websocket-request-expected! ch ssl?)
                    (d/error-deferred e)))))
          (catch Throwable e
            (d/error-deferred e)))
        (do
          (WebSocketServerHandshakerFactory/sendUnsupportedVersionResponse ch)
          (d/error-deferred (IllegalStateException. "unsupported version"))))
      (finally
        ;; I find this approach to handle request release somewhat
        ;; fragile... We have to release the object both in case of
        ;; handshake initialization and "unsupported version" response
        (netty/release req)))))
