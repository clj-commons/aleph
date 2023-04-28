(ns aleph.http.websocket.client
  (:require
    [aleph.http.core :as http]
    [aleph.http.websocket.common :as ws.common]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelPipeline)
    (io.netty.handler.codec.http
      HttpClientCodec
      DefaultHttpHeaders
      HttpResponse
      FullHttpResponse
      HttpObjectAggregator)
    (io.netty.handler.codec.http.websocketx
      CloseWebSocketFrame
      PingWebSocketFrame
      PongWebSocketFrame
      TextWebSocketFrame
      BinaryWebSocketFrame
      WebSocketClientHandshaker
      WebSocketClientHandshakerFactory
      WebSocketFrame
      WebSocketFrameAggregator
      WebSocketVersion)
    (io.netty.handler.codec.http.websocketx.extensions.compression
      WebSocketClientCompressionHandler)
    (io.netty.handler.timeout
      IdleState
      IdleStateEvent)
    (java.net
      URI
      InetSocketAddress)
    (java.util.concurrent
      ConcurrentLinkedQueue)
    (java.util.concurrent.atomic
      AtomicBoolean)))


(defn websocket-frame-size [^WebSocketFrame frame]
  (-> frame .content .readableBytes))

(defn websocket-handshaker
  ^WebSocketClientHandshaker
  [uri sub-protocols extensions? headers max-frame-payload]
  (WebSocketClientHandshakerFactory/newHandshaker
    uri
    WebSocketVersion/V13
    sub-protocols
    extensions?
    (doto (DefaultHttpHeaders.) (http/map->headers! headers))
    max-frame-payload))

(defn websocket-client-handler
  ([raw-stream?
    uri
    sub-protocols
    extensions?
    headers
    max-frame-payload]
   (websocket-client-handler raw-stream?
                             uri
                             sub-protocols
                             extensions?
                             headers
                             max-frame-payload
                             nil))
  ([raw-stream?
    uri
    sub-protocols
    extensions?
    headers
    max-frame-payload
    heartbeats]
   (let [d (d/deferred)
         in (atom nil)
         desc (atom {})
         ^ConcurrentLinkedQueue pending-pings (ConcurrentLinkedQueue.)
         handshaker (websocket-handshaker uri
                                          sub-protocols
                                          extensions?
                                          headers
                                          max-frame-payload)
         closing? (AtomicBoolean. false)]

     [d

      (netty/channel-inbound-handler

        :exception-caught
        ([_ ctx ex]
         (when-not (d/error! d ex)
           (log/warn ex "error in websocket client"))
         (s/close! @in)
         (netty/close ctx))

        :channel-inactive
        ([_ ctx]
         (when (realized? d)
           ;; close only on success
           (d/chain' d s/close!)
           (ws.common/resolve-pings! pending-pings false))
         (.fireChannelInactive ctx))

        :channel-active
        ([_ ctx]
         (-> (.channel ctx)
             netty/maybe-ssl-handshake-future
             (d/on-realized (fn [ch]
                              (reset! in (netty/buffered-source ch (constantly 1) 16))
                              (.handshake handshaker ch))
                            netty/ignore-ssl-handshake-errors))
         (.fireChannelActive ctx))

        :user-event-triggered
        ([_ ctx evt]
         (if (and (instance? IdleStateEvent evt)
                  (= IdleState/ALL_IDLE (.state ^IdleStateEvent evt)))
           (when (d/realized? d)
             (ws.common/handle-heartbeat ctx @d heartbeats))
           (.fireUserEventTriggered ctx evt)))

        :channel-read
        ([_ ctx msg]
         (let [ch ^Channel (.channel ctx)]
           (cond

             (not (.isHandshakeComplete handshaker))
             (try
               ;; Here we rely on the HttpObjectAggregator being added
               ;; to the pipeline in advance, so there's no chance we
               ;; could read only a partial request
               (.finishHandshake handshaker ch msg)
               (let [close-fn (fn [^CloseWebSocketFrame frame]
                                (if-not (.compareAndSet closing? false true)
                                  (do
                                    (netty/release frame)
                                    false)
                                  (do
                                    (-> (.close handshaker ch frame)
                                        netty/wrap-future
                                        (d/chain' (fn [_] (netty/close ctx))))
                                    true)))
                     coerce-fn (ws.common/websocket-message-coerce-fn
                                 ch
                                 pending-pings
                                 close-fn)
                     headers (http/headers->map (.headers ^HttpResponse msg))
                     subprotocol (.actualSubprotocol handshaker)
                     _ (swap! desc assoc
                              :websocket-handshake-headers headers
                              :websocket-selected-subprotocol subprotocol)
                     out (netty/sink ch false coerce-fn (fn [] @desc))]

                 (s/on-closed out #(ws.common/resolve-pings! pending-pings false))

                 (d/success! d
                             (doto
                               (s/splice out @in)
                               (reset-meta! {:aleph/channel ch})))

                 (s/on-drained @in #(close-fn (CloseWebSocketFrame.))))
               (catch Throwable ex
                 ;; handle handshake exception
                 (d/error! d ex)
                 (s/close! @in)
                 (netty/close ctx))
               (finally
                 (netty/release msg)))

             (instance? FullHttpResponse msg)
             (let [rsp ^FullHttpResponse msg
                   content (bs/to-string (.content rsp))]
               (netty/release msg)
               (throw
                 (IllegalStateException.
                   (str "unexpected HTTP response, status: "
                        (.status rsp)
                        ", body: '"
                        content
                        "'"))))

             (instance? TextWebSocketFrame msg)
             (if raw-stream?
               (let [body (.content ^TextWebSocketFrame msg)]
                 ;; pass ByteBuf body directly to lower next
                 ;; level. it's their reponsibility to release
                 (netty/put! ch @in body))
               (let [text (.text ^TextWebSocketFrame msg)]
                 (netty/release msg)
                 (netty/put! ch @in text)))

             (instance? BinaryWebSocketFrame msg)
             (let [frame (.content ^BinaryWebSocketFrame msg)]
               (netty/put! ch @in
                           (if raw-stream?
                             frame
                             (netty/release-buf->array frame))))

             (instance? PongWebSocketFrame msg)
             (do
               (netty/release msg)
               (ws.common/resolve-pings! pending-pings true))

             (instance? PingWebSocketFrame msg)
             (let [frame (.content ^PingWebSocketFrame msg)]
               (netty/write-and-flush ch (PongWebSocketFrame. frame)))

             ;; todo(kachayev): check RFC what should we do in case
             ;;                 we've got > 1 closing frame from the
             ;;                 server
             (instance? CloseWebSocketFrame msg)
             (let [frame ^CloseWebSocketFrame msg]
               (when (realized? d)
                 (swap! desc assoc
                        :websocket-close-code (.statusCode frame)
                        :websocket-close-msg (.reasonText frame)))
               (netty/release msg)
               (netty/close ctx))

             :else
             (.fireChannelRead ctx msg)))))])))

(defn websocket-connection
  [uri
   {:keys [raw-stream?
           insecure?
           ssl-context
           headers
           local-address
           bootstrap-transform
           pipeline-transform
           epoll?
           transport
           sub-protocols
           extensions?
           max-frame-payload
           max-frame-size
           compression?
           heartbeats]
    :or   {bootstrap-transform identity
           pipeline-transform  identity
           raw-stream?         false
           epoll?              false
           sub-protocols       nil
           extensions?         false
           max-frame-payload   65536
           max-frame-size      1048576
           compression?        false}
    :as   options}]

  (when (and (true? (:compression? options))
             (false? (:extensions? options)))
    (throw (IllegalArgumentException.
             "Per-message deflate requires extensions to be allowed")))

  (let [uri (URI. uri)
        scheme (.getScheme uri)
        _ (assert (#{"ws" "wss"} scheme) "scheme must be one of 'ws' or 'wss'")
        ssl? (= "wss" scheme)
        heartbeats (when (some? heartbeats)
                     (merge
                       {:send-after-idle 3e4
                        :payload         nil
                        :timeout         nil}
                       heartbeats))
        [s handler] (websocket-client-handler
                      raw-stream?
                      uri
                      sub-protocols
                      (or extensions? compression?)
                      headers
                      max-frame-payload
                      heartbeats)]
    (d/chain'
      (netty/create-client-chan
        {:pipeline-builder    (fn [^ChannelPipeline pipeline]
                                (doto pipeline
                                      (.addLast "http-client" (HttpClientCodec.))
                                      (.addLast "aggregator" (HttpObjectAggregator. 16384))
                                      (.addLast "websocket-frame-aggregator" (WebSocketFrameAggregator. max-frame-size))
                                      (#(when compression?
                                          (.addLast ^ChannelPipeline %
                                                    "websocket-deflater"
                                                    WebSocketClientCompressionHandler/INSTANCE)))
                                      (ws.common/attach-heartbeats-handler heartbeats)
                                      (.addLast "handler" ^ChannelHandler handler)
                                      pipeline-transform))
         :ssl-context         (when ssl?
                                (or ssl-context
                                    (if insecure?
                                      (netty/insecure-ssl-client-context)
                                      (netty/ssl-client-context))))
         :bootstrap-transform bootstrap-transform
         :remote-address      (InetSocketAddress.
                                (.getHost uri)
                                (int
                                  (if (neg? (.getPort uri))
                                    (if ssl? 443 80)
                                    (.getPort uri))))
         :local-address       local-address
         :transport           (netty/determine-transport transport epoll?)})
      (fn [_] s))))
