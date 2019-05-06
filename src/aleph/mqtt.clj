(ns aleph.mqtt
  (:require [aleph.netty :as netty]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [manifold.bus :as bus])
  (:import
   [java.io Writer]
   [java.net InetSocketAddress]
   [java.io IOException]
   [io.netty.channel
    Channel
    ChannelHandler
    ChannelPipeline]
   [io.netty.handler.ssl
    SslHandler]
   [io.netty.handler.codec.mqtt
    MqttMessageBuilders
    MqttEncoder
    MqttDecoder
    MqttConnAckMessage
    MqttConnectReturnCode
    MqttFixedHeader
    MqttMessage
    MqttMessageIdVariableHeader
    MqttQoS
    MqttMessageType]))

(deftype MqttConnection [stream packet-ids router])

(defmethod print-method MqttConnection [^MqttConnection conn ^Writer w]
  (.write w (format "MqttConnection[channel: %s, last packet: %s]"
                    (:aleph/channel (meta (.-stream conn)))
                    @(.-packet-ids conn))))

;; todo(kachayev): this should be expected for subscriptions
;; and for exactly-once publish (as we have 2 different acks
;; there)
(defn- packet-id [packet]
  (when (instance? MqttMessage packet)
    (let [header (.variableHeader ^MqttMessage packet)]
      (when (instance? MqttMessageIdVariableHeader header)
        (.messageId ^MqttMessageIdVariableHeader header)))))

;; todo(kachayev): this should be expected for subscriptions
;; and for exactly-once publish (as we have 2 different acks
;; there)
(defn- coerce-packet [packet]
  (when (instance? MqttMessage packet)
    (let [^MqttFixedHeader header (.fixedHeader ^MqttMessage packet)]
      {:message-type (.messageType header)
       :dup? (.isDup header)
       :qos (.qosLevel header)
       :retained? (.isRetain header)
       :lenght (.remainingLength header)})))

(defn- stream->mqtt-connection [stream]
  (let [router (bus/event-bus)]
    ;; todo(kachayev): most probably it's better to implement
    ;; this as a Netty handler to get most performance
    ;; but as for now we can stick to manifold streams
    ;; to be sure that auto read switches correctly and
    ;; we're not going to read all available messages into
    ;; memory right away
    (->> stream
         (s/consume-async
          (fn [packet]
            (try
              (let [id (packet-id packet)
                    cp (when (some? id)
                         (coerce-packet packet))]
                (if (some? cp)
                  (d/chain'
                   ;; note, that this would yield only when all
                   ;; subscribers got messages
                   (bus/publish! router id cp)
                   (fn [_] true))
                  ;; todo(kachayev): curious what's there and if we
                  ;; need to fail reading at least for debug purpose
                  (d/success-deferred true)))
              (finally
                (netty/release packet))))))
    (MqttConnection. stream (atom 0) router)))

(defn- send! [^MqttConnection conn packet]
  (s/put! (.-stream conn) packet))

(defn- next-packet-id [^MqttConnection conn]
  (int (swap! (.-packet-ids conn) #(if (= Integer/MAX_VALUE %1) 0 %1))))

(defn- create-listener [^MqttConnection conn packet-id]
  (bus/subscribe (.-router conn) packet-id))

(defn- cancel-listener! [stream]
  (s/close! stream))

;; todo(kachayev): expose max bytes in message configuration
(defn- pipeline-transform [handler ^ChannelPipeline pipeline]
  (.addLast pipeline "encoder" MqttEncoder/INSTANCE)
  (.addLast pipeline "decoder" (MqttDecoder.))
  (.addLast pipeline "handler" ^ChannelHandler handler))

(defn- ^ChannelHandler client-channel-handler []
  (let [d (d/deferred)
        in (atom nil)]
    [d

     (netty/channel-inbound-handler

       :exception-caught
       ([_ ctx ex]
         (when-not (d/error! d ex)
           (log/warn ex "error in MQTT client")))

       :channel-inactive
       ([_ ctx]
         (s/close! @in)
         (.fireChannelInactive ctx))

       :channel-active
       ([_ ctx]
         (let [ch (.channel ctx)]
           (d/success! d
             (doto
               (s/splice
                 (netty/sink ch true identity)
                 (reset! in (netty/source ch)))
               (reset-meta! {:aleph/channel ch}))))
         (.fireChannelActive ctx))

       :channel-read
       ([_ ctx msg]
         (netty/put! (.channel ctx) @in msg))

       :close
       ([_ ctx promise]
         (.close ctx promise)
         (d/error! d (IllegalStateException. "unable to connect"))))]))

;; todo(kachayev): documentation
(defn- client
  [{:keys [host
           port
           remote-address
           local-address
           ssl-context
           ssl?
           insecure?
           epoll?]
    :or {epoll? false}
    :as options}]
  (let [[s handler] (client-channel-handler)]
    (-> (netty/create-client
         (partial pipeline-transform handler)
         (if ssl-context
           ssl-context
           (when ssl?
             (if insecure?
               (netty/insecure-ssl-client-context)
               (netty/ssl-client-context))))
         identity
         (or remote-address (InetSocketAddress. ^String host (int port)))
         local-address
         epoll?)
        (d/catch' #(d/error! s %)))
    s))

(defn- handle-conn-ack [conn ^MqttConnAckMessage packet]
  (let [code (.connectReturnCode (.variableHeader packet))]
    (if (.equals code MqttConnectReturnCode/CONNECTION_ACCEPTED)
      (stream->mqtt-connection conn)
      (do
        (s/close! conn)
        (d/error-deferred (RuntimeException. (str "conn failed: " code)))))))

;; todo(kachayev): it's way better to have connection
;; packet to be sent using pipeline handler, but for
;; the experiment it's good as is
;;
;; todo(kachayev): reconnection logic should be also
;; defined either here or as a separate pipeline handler
;;
;; todo(kachayev): make all configuration params actually work
;; todo(kachayev): docs
(defn connect [{:keys [host
                       port
                       remote-address
                       local-address
                       ssl-context
                       ssl?
                       insecure?
                       epoll?

                       client-id
                       clean-session?
                       keep-alive-ms
                       will-flag?
                       will-qos
                       will-topic
                       will-message
                       will-retain?
                       username
                       password]
                :as options}]
  (assert (some? client-id) "Client ID must be set")
  ;; todo(kachayev): handler timeout & exceptions
  (d/chain'
   (client (select-keys options [:host
                                 :port
                                 :remote-address
                                 :local-address
                                 :ssl-context
                                 :ssl?
                                 :insecure?
                                 :epoll?]))
   (fn [conn]
     (let [conn-req (doto (MqttMessageBuilders/connect)
                      (.clientId client-id))]
       ;; todo(kachayev): handle timeout here
       (d/chain'
        (s/put! conn (.build conn-req))
        (fn [sent]
          (if-not sent
            (d/error-deferred (RuntimeException. "conn closed"))
            (d/chain'
             (s/take! conn ::drained)
             (fn [packet]
               (cond
                 (identical? ::drained packet)
                 (d/error-deferred (RuntimeException. "conn closed"))

                 ;; todo(kachayev): expose more information about connection
                 (instance? MqttConnAckMessage packet)
                 (handle-conn-ack conn packet)

                 :else
                 (do
                   (s/close! conn)
                   (d/error-deferred
                    (IllegalStateException.
                     "conn failed due to unexpected packet")))))))))))))

(defn- ^MqttQoS coerce-qos [qos]
  (if (instance? MqttQoS qos)
    qos
    (case qos
      :at-most-once  MqttQoS/AT_MOST_ONCE
      :at-least-once MqttQoS/AT_LEAST_ONCE
      :exactly-once  MqttQoS/EXACTLY_ONCE)))

;; todo(kachayev): support exactly-once flow
(defn publish
  "Publish message to a broker with specified topic and payload.

   Options

   |:--- |:--- |
   | `:qos` | MQTT Quality of Service, should be one of `:at-most-once`, `:at-least-once`, `:exactly-once`. Defaults to `:at-most-once`. |
   | `:retained?` | defines if the message should be saved by the broker as the last known value for a given topic, defaults to `false`. |

   If `:qos` option is set to `:at-most-once` (or skipped), function
   returns deferred that yields `true` as soon as the message was flushed
   to the connection. In any other case it yield only when appropriate
   acknowledgement is recieved from the broker."
  ([client topic message]
   (publish client topic message {}))
  ([client topic message {:keys [retained? qos]
                          :or {qos :at-most-once
                               retained? false}}]
   (let [qos-value (coerce-qos qos)
         at-most-once? (identical? MqttQoS/AT_MOST_ONCE qos-value)
         packet-id (when-not at-most-once?
                     (next-packet-id client))
         packet (doto (MqttMessageBuilders/publish)
                  (.topicName topic)
                  (.qos qos-value)
                  (.payload (netty/to-byte-buf message)))
         listener (when (some? packet-id)
                    (.messageId packet packet-id)
                    (create-listener client packet-id))]
     (d/chain'
      (send! client (.build packet))
      (fn [r]
        (if-not r
          ;; todo(kachayev): ideally, we can wait for reconnect
          ;; to happen before giving up on publishing the message
          (d/error-deferred (RuntimeException. "conn closed"))
          (if at-most-once?
            true
            ;; todo(kachayev): publish ack timeout
            ;; todo(kachayev): publish retries
            (-> (s/take! listener)
                (d/chain'
                 (fn [{:keys [message-type]}]
                   (if (identical? MqttMessageType/PUBACK message-type)
                     true
                     (d/error-deferred
                      (IllegalStateException.
                       "publish ack expected, got" message-type)))))
                (d/finally'
                  (fn []
                    (cancel-listener! listener)))))))))))

(defn subscribe [client topic])

(defn unsubscribe [client topic])

(defn disconnect [client])
