(ns aleph.netty
  (:refer-clojure :exclude [flush])
  (:require
    [clj-commons.byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.executor :as e]
    [manifold.stream :as s]
    [manifold.stream.core :as manifold]
    [clj-commons.primitive-math :as p]
    [clojure
     [string :as str]
     [set :as set]]
    [potemkin :as potemkin :refer [doit doary]])
  (:import
    [java.io IOException]
    [io.netty.bootstrap Bootstrap ServerBootstrap]
    [io.netty.buffer ByteBuf Unpooled]
    [io.netty.channel
     Channel ChannelFuture ChannelOption
     ChannelPipeline EventLoopGroup
     ChannelHandler FileRegion
     ChannelInboundHandler
     ChannelOutboundHandler
     ChannelHandlerContext
     ChannelInitializer]
    [io.netty.channel.epoll Epoll EpollEventLoopGroup
     EpollServerSocketChannel
     EpollSocketChannel
     EpollDatagramChannel]
    [io.netty.util Attribute AttributeKey]
    [io.netty.handler.codec Headers]
    [io.netty.channel.nio NioEventLoopGroup]
    [io.netty.channel.socket ServerSocketChannel]
    [io.netty.channel.socket.nio
     NioServerSocketChannel
     NioSocketChannel
     NioDatagramChannel]
    [io.netty.handler.ssl
     SslContext
     SslContextBuilder
     SslHandler]
    [io.netty.handler.ssl.util
     SelfSignedCertificate InsecureTrustManagerFactory]
    [io.netty.resolver
     AddressResolverGroup
     NoopAddressResolverGroup
     ResolvedAddressTypes]
    [io.netty.resolver.dns
     DnsNameResolverBuilder
     DnsAddressResolverGroup
     DnsServerAddressStreamProvider
     SingletonDnsServerAddressStreamProvider
     SequentialDnsServerAddressStreamProvider]
    [io.netty.util ResourceLeakDetector
     ResourceLeakDetector$Level]
    [java.net URI SocketAddress InetSocketAddress]
    [io.netty.util.concurrent
     GenericFutureListener Future]
    [java.io InputStream File]
    [java.nio ByteBuffer]
    [io.netty.util.internal SystemPropertyUtil]
    [java.util.concurrent
     ConcurrentHashMap
     CancellationException
     ScheduledFuture
     TimeUnit
     ThreadFactory]
    [java.util.concurrent.atomic
     AtomicLong]
    [io.netty.util.internal.logging
     InternalLoggerFactory
     Log4JLoggerFactory
     Slf4JLoggerFactory
     JdkLoggerFactory
     Log4J2LoggerFactory]
    [io.netty.handler.logging
     LoggingHandler
     LogLevel]
    [java.security.cert X509Certificate]
    [java.security PrivateKey]))

;;;

(definline release [x]
  `(io.netty.util.ReferenceCountUtil/release ~x))

(definline acquire [x]
  `(io.netty.util.ReferenceCountUtil/retain ~x))

(defn leak-detector-level! [level]
  (ResourceLeakDetector/setLevel
    (case level
      :disabled ResourceLeakDetector$Level/DISABLED
      :simple ResourceLeakDetector$Level/SIMPLE
      :advanced ResourceLeakDetector$Level/ADVANCED
      :paranoid ResourceLeakDetector$Level/PARANOID)))

(defn set-logger! [logger]
  (InternalLoggerFactory/setDefaultFactory
    (case logger
      :log4j  Log4JLoggerFactory/INSTANCE
      :log4j2 Log4J2LoggerFactory/INSTANCE
      :slf4j  Slf4JLoggerFactory/INSTANCE
      :jdk    JdkLoggerFactory/INSTANCE)))

;;;

(defn channel-server-name [^Channel ch]
  (some-> ch ^InetSocketAddress (.localAddress) .getHostName))

(defn channel-server-port [^Channel ch]
  (some-> ch ^InetSocketAddress (.localAddress) .getPort))

(defn channel-remote-address [^Channel ch]
  (some-> ch ^InetSocketAddress (.remoteAddress) .getAddress .getHostAddress))

;;;

(def ^:const array-class (class (clojure.core/byte-array 0)))

(defn buf->array [^ByteBuf buf]
  (let [dst (ByteBuffer/allocate (.readableBytes buf))]
    (doary [^ByteBuffer buf (.nioBuffers buf)]
      (.put dst buf))
    (.array dst)))

(defn release-buf->array [^ByteBuf buf]
  (try
    (buf->array buf)
    (finally
      (release buf))))

(defn bufs->array [bufs]
  (let [bufs' (mapcat #(.nioBuffers ^ByteBuf %) bufs)
        dst (ByteBuffer/allocate
              (loop [cnt 0, s bufs']
                (if (empty? s)
                  cnt
                  (recur (p/+ cnt (.remaining ^ByteBuffer (first s))) (rest s)))))]
    (doit [^ByteBuffer buf bufs']
      (.put dst buf))
    (.array dst)))

(bs/def-conversion ^{:cost 1} [ByteBuf array-class]
  [buf options]
  (let [ary (buf->array buf)]
    (release buf)
    ary))

(bs/def-conversion ^{:cost 0} [ByteBuffer ByteBuf]
  [buf options]
  (Unpooled/wrappedBuffer buf))

(declare allocate)

(let [charset (java.nio.charset.Charset/forName "UTF-8")]
  (defn append-to-buf! [^ByteBuf buf x]
    (cond
      (instance? array-class x)
      (.writeBytes buf ^bytes x)

      (instance? String x)
      (.writeBytes buf (.getBytes ^String x charset))

      (instance? ByteBuf x)
      (let [b (.writeBytes buf ^ByteBuf x)]
        (release x)
        b)

      :else
      (.writeBytes buf (bs/to-byte-buffer x))))

  (defn ^ByteBuf to-byte-buf
    ([x]
     (cond
       (nil? x)
       Unpooled/EMPTY_BUFFER

       (instance? array-class x)
       (Unpooled/copiedBuffer ^bytes x)

       (instance? String x)
       (-> ^String x (.getBytes charset) ByteBuffer/wrap Unpooled/wrappedBuffer)

       (instance? ByteBuffer x)
       (Unpooled/wrappedBuffer ^ByteBuffer x)

       (instance? ByteBuf x)
       x

       :else
       (bs/convert x ByteBuf)))
    ([ch x]
     ;; todo(kachayev): do we really need to reallocate in case
     ;;                 we already have an instance of ByteBuf here?
     (if (nil? x)
       Unpooled/EMPTY_BUFFER
       (doto (allocate ch)
         (append-to-buf! x))))))

(defn to-byte-buf-stream [x chunk-size]
  (->> (bs/convert x (bs/stream-of ByteBuf) {:chunk-size chunk-size})
    (s/onto nil)))

(defn wrap-future
  [^Future f]
  (when f
    (if (.isSuccess f)
      (d/success-deferred (.getNow f) nil)
      (let [d (d/deferred nil)
            ;; workaround for the issue with executing RT.readString
            ;; on a Netty thread that doesn't have appropriate class loader
            ;; more information here:
            ;; https://github.com/clj-commons/aleph/issues/365
            class-loader (or (clojure.lang.RT/baseLoader)
                             (clojure.lang.RT/makeClassLoader))]
        (.addListener f
          (reify GenericFutureListener
            (operationComplete [_ _]
              (try
                (. clojure.lang.Var
                   (pushThreadBindings {clojure.lang.Compiler/LOADER
                                        class-loader}))
                (cond
                  (.isSuccess f)
                  (d/success! d (.getNow f))

                  (.isCancelled f)
                  (d/error! d (CancellationException. "future is cancelled."))

                  (some? (.cause f))
                  (if (instance? java.nio.channels.ClosedChannelException (.cause f))
                    (d/success! d false)
                    (d/error! d (.cause f)))

                  :else
                  (d/error! d (IllegalStateException. "future in unknown state")))
                (finally
                  (. clojure.lang.Var (popThreadBindings)))))))
        d))))

(defn allocate [x]
  (if (instance? Channel x)
    (-> ^Channel x .alloc .ioBuffer)
    (-> ^ChannelHandlerContext x .alloc .ioBuffer)))

(defn write [x msg]
  (if (instance? Channel x)
    (.write ^Channel x msg (.voidPromise ^Channel x))
    (.write ^ChannelHandlerContext x msg (.voidPromise ^ChannelHandlerContext x)))
  nil)

(defn write-and-flush
  [x msg]
  (if (instance? Channel x)
    (.writeAndFlush ^Channel x msg)
    (.writeAndFlush ^ChannelHandlerContext x msg)))

(defn flush [x]
  (if (instance? Channel x)
    (.flush ^Channel x)
    (.flush ^ChannelHandlerContext x)))

(defn close [x]
  (if (instance? Channel x)
    (.close ^Channel x)
    (.close ^ChannelHandlerContext x)))

(defn ^Channel channel [x]
  (if (instance? Channel x)
    x
    (.channel ^ChannelHandlerContext x)))

(defmacro safe-execute [ch & body]
  `(let [f# (fn [] ~@body)
         event-loop# (-> ~ch aleph.netty/channel .eventLoop)]
     (if (.inEventLoop event-loop#)
       (f#)
       (let [d# (d/deferred)]
         (.execute event-loop#
           (fn []
             (try
               (d/success! d# (f#))
               (catch Throwable e#
                 (d/error! d# e#)))))
         d#))))

(defn put! [^Channel ch s msg]
  (let [d (s/put! s msg)]
    (d/success-error-unrealized d

      val (if val
            true
            (do
              (release msg)
              (.close ch)
              false))

      err  (do
             (release msg)
             (.close ch)
             false)

      (do

        ;; enable backpressure
        (-> ch .config (.setAutoRead false))

        (-> d
          (d/finally'
            (fn []
              ;; disable backpressure
              (-> ch .config (.setAutoRead true))))
          (d/chain'
            (fn [result]
              (when-not result
                (release msg)
                (.close ch)))))
        d))))

;;;

(defn attribute [s]
  (AttributeKey/valueOf (name s)))

(defn get-attribute [ch attr]
  (-> ch channel ^Attribute (.attr attr) .get))

(defn set-attribute [ch attr val]
  (-> ch channel ^Attribute (.attr attr) (.set val)))

;;;

(def ^ConcurrentHashMap channel-inbound-counter     (ConcurrentHashMap.))
(def ^ConcurrentHashMap channel-outbound-counter    (ConcurrentHashMap.))
(def ^ConcurrentHashMap channel-inbound-throughput  (ConcurrentHashMap.))
(def ^ConcurrentHashMap channel-outbound-throughput (ConcurrentHashMap.))

(defn- connection-stats [^Channel ch inbound?]
  (merge
    {:local-address (str (.localAddress ch))
     :remote-address (str (.remoteAddress ch))
     :writable? (.isWritable ch)
     :readable? (-> ch .config .isAutoRead)
     :closed? (not (.isActive ch))}
    (let [^ConcurrentHashMap throughput (if inbound?
                                          channel-inbound-throughput
                                          channel-outbound-throughput)]
      (when-let [^AtomicLong throughput (.get throughput ch)]
        {:throughput (.get throughput)}))))

(def sink-close-marker ::sink-close)

(manifold/def-sink ChannelSink
  [coerce-fn
   downstream?
   ^Channel ch
   additional-description]
  (close [this]
    (when downstream?
      (close ch))
    (.markClosed this)
    true)
  (description [_]
    (let [ch (channel ch)]
      (merge
        {:type       "netty"
         :closed?    (not (.isActive ch))
         :sink?      true
         :connection (assoc (connection-stats ch false)
                       :direction :outbound)}
        (additional-description))))
  (isSynchronous [_]
    false)
  (put [this msg blocking?]
    (if (s/closed? this)
      (if blocking?
        false
        (d/success-deferred false))
      (let [msg (try
                  (coerce-fn msg)
                  (catch Exception e
                    (log/error e
                      (str "cannot coerce "
                        (.getName (class msg))
                        " into binary representation"))
                    (close ch)))
            d (cond
                (nil? msg)
                (d/success-deferred true)

                (identical? sink-close-marker msg)
                (do
                  (.markClosed this)
                  (d/success-deferred false))

                :else
                (let [^ChannelFuture f (write-and-flush ch msg)]
                  (-> f
                    wrap-future
                    (d/chain' (fn [_] true))
                    (d/catch' IOException (fn [_] false)))))]
        (if blocking?
          @d
          d))))
  (put [this msg blocking? timeout timeout-value]
    (.put this msg blocking?)))



(defn sink
  ([ch]
    (sink ch true identity (fn [])))
  ([ch downstream? coerce-fn]
    (sink ch downstream? coerce-fn (fn [])))
  ([ch downstream? coerce-fn additional-description]
    (let [sink (->ChannelSink
                 coerce-fn
                 downstream?
                 ch
                 additional-description)]

      (d/chain'
        (wrap-future (.closeFuture (channel ch)))
        (fn [_] (s/close! sink)))

      (doto sink (reset-meta! {:aleph/channel ch})))))

(defn source
  [^Channel ch]
  (let [src (s/stream*
              {:description
               (fn [m]
                 (assoc m
                   :type "netty"
                   :direction :inbound
                   :connection (assoc (connection-stats ch true)
                                 :direction :inbound)))})]
    (doto src (reset-meta! {:aleph/channel ch}))))

(defn buffered-source
  [^Channel ch metric capacity]
  (let [src (s/buffered-stream
              metric
              capacity
              (fn [m]
                (assoc m
                  :type "netty"
                  :connection (assoc (connection-stats ch true)
                                :direction :inbound))))]
    (doto src (reset-meta! {:aleph/channel ch}))))

;;;

(defmacro channel-handler
  [& {:as handlers}]
  `(reify
     ChannelHandler
     ChannelInboundHandler
     ChannelOutboundHandler

     (handlerAdded
       ~@(or (:handler-added handlers) `([_# _#])))
     (handlerRemoved
       ~@(or (:handler-removed handlers) `([_# _#])))
     (exceptionCaught
       ~@(or (:exception-caught handlers)
           `([_# ctx# cause#]
              (.fireExceptionCaught ctx# cause#))))
     (channelRegistered
       ~@(or (:channel-registered handlers)
           `([_# ctx#]
              (.fireChannelRegistered ctx#))))
     (channelUnregistered
       ~@(or (:channel-unregistered handlers)
           `([_# ctx#]
              (.fireChannelUnregistered ctx#))))
     (channelActive
       ~@(or (:channel-active handlers)
           `([_# ctx#]
              (.fireChannelActive ctx#))))
     (channelInactive
       ~@(or (:channel-inactive handlers)
           `([_# ctx#]
              (.fireChannelInactive ctx#))))
     (channelRead
       ~@(or (:channel-read handlers)
           `([_# ctx# msg#]
              (.fireChannelRead ctx# msg#))))
     (channelReadComplete
       ~@(or (:channel-read-complete handlers)
           `([_# ctx#]
              (.fireChannelReadComplete ctx#))))
     (userEventTriggered
       ~@(or (:user-event-triggered handlers)
           `([_# ctx# evt#]
              (.fireUserEventTriggered ctx# evt#))))
     (channelWritabilityChanged
       ~@(or (:channel-writability-changed handlers)
           `([_# ctx#]
              (.fireChannelWritabilityChanged ctx#))))
     (bind
       ~@(or (:bind handlers)
           `([_# ctx# local-address# promise#]
              (.bind ctx# local-address# promise#))))
     (connect
       ~@(or (:connect handlers)
           `([_# ctx# remote-address# local-address# promise#]
              (.connect ctx# remote-address# local-address# promise#))))
     (disconnect
       ~@(or (:disconnect handlers)
           `([_# ctx# promise#]
              (.disconnect ctx# promise#))))
     (close
       ~@(or (:close handlers)
           `([_# ctx# promise#]
              (.close ctx# promise#))))
     (read
       ~@(or (:read handlers)
           `([_# ctx#]
              (.read ctx#))))
     (write
       ~@(or (:write handlers)
           `([_# ctx# msg# promise#]
              (.write ctx# msg# promise#))))
     (flush
       ~@(or (:flush handlers)
           `([_# ctx#]
             (.flush ctx#))))))

(defmacro channel-inbound-handler
  [& {:as handlers}]
  `(reify
     ChannelHandler
     ChannelInboundHandler

     (handlerAdded
       ~@(or (:handler-added handlers) `([_# _#])))
     (handlerRemoved
       ~@(or (:handler-removed handlers) `([_# _#])))
     (exceptionCaught
       ~@(or (:exception-caught handlers)
           `([_# ctx# cause#]
              (.fireExceptionCaught ctx# cause#))))
     (channelRegistered
       ~@(or (:channel-registered handlers)
           `([_# ctx#]
              (.fireChannelRegistered ctx#))))
     (channelUnregistered
       ~@(or (:channel-unregistered handlers)
           `([_# ctx#]
              (.fireChannelUnregistered ctx#))))
     (channelActive
       ~@(or (:channel-active handlers)
           `([_# ctx#]
              (.fireChannelActive ctx#))))
     (channelInactive
       ~@(or (:channel-inactive handlers)
           `([_# ctx#]
              (.fireChannelInactive ctx#))))
     (channelRead
       ~@(or (:channel-read handlers)
           `([_# ctx# msg#]
              (.fireChannelRead ctx# msg#))))
     (channelReadComplete
       ~@(or (:channel-read-complete handlers)
           `([_# ctx#]
              (.fireChannelReadComplete ctx#))))
     (userEventTriggered
       ~@(or (:user-event-triggered handlers)
           `([_# ctx# evt#]
              (.fireUserEventTriggered ctx# evt#))))
     (channelWritabilityChanged
       ~@(or (:channel-writability-changed handlers)
           `([_# ctx#]
              (.fireChannelWritabilityChanged ctx#))))))

(defmacro channel-outbound-handler
  [& {:as handlers}]
  `(reify
     ChannelHandler
     ChannelOutboundHandler

     (handlerAdded
       ~@(or (:handler-added handlers) `([_# _#])))
     (handlerRemoved
       ~@(or (:handler-removed handlers) `([_# _#])))
     (exceptionCaught
       ~@(or (:exception-caught handlers)
           `([_# ctx# cause#]
              (.fireExceptionCaught ctx# cause#))))
     (bind
       ~@(or (:bind handlers)
           `([_# ctx# local-address# promise#]
              (.bind ctx# local-address# promise#))))
     (connect
       ~@(or (:connect handlers)
           `([_# ctx# remote-address# local-address# promise#]
              (.connect ctx# remote-address# local-address# promise#))))
     (disconnect
       ~@(or (:disconnect handlers)
           `([_# ctx# promise#]
              (.disconnect ctx# promise#))))
     (close
       ~@(or (:close handlers)
           `([_# ctx# promise#]
              (.close ctx# promise#))))
     (read
       ~@(or (:read handlers)
           `([_# ctx#]
              (.read ctx#))))
     (write
       ~@(or (:write handlers)
           `([_# ctx# msg# promise#]
              (.write ctx# msg# promise#))))
     (flush
       ~@(or (:flush handlers)
           `([_# ctx#]
              (.flush ctx#))))))

(defn ^ChannelHandler bandwidth-tracker [^Channel ch]
  (let [inbound-counter (AtomicLong. 0)
        outbound-counter (AtomicLong. 0)
        inbound-throughput (AtomicLong. 0)
        outbound-throughput (AtomicLong. 0)

        ^ScheduledFuture future
        (.scheduleAtFixedRate (-> ch .eventLoop .parent)
          (fn []
            (.set inbound-throughput (.getAndSet inbound-counter 0))
            (.set outbound-throughput (.getAndSet outbound-counter 0)))
          1000
          1000
          TimeUnit/MILLISECONDS)]

    (.put channel-inbound-counter ch inbound-counter)
    (.put channel-outbound-counter ch outbound-counter)
    (.put channel-inbound-throughput ch inbound-throughput)
    (.put channel-outbound-throughput ch outbound-throughput)

    (channel-handler

      :channel-inactive
      ([_ ctx]
        (.cancel future true)
        (.remove channel-inbound-counter ch)
        (.remove channel-outbound-counter ch)
        (.remove channel-inbound-throughput ch)
        (.remove channel-outbound-throughput ch)
        (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
        (.addAndGet inbound-counter
          (if (instance? FileRegion msg)
            (.count ^FileRegion msg)
            (.readableBytes ^ByteBuf msg)))
        (.fireChannelRead ctx msg))

      :write
      ([_ ctx msg promise]
        (.addAndGet outbound-counter
          (if (instance? FileRegion msg)
            (.count ^FileRegion msg)
            (.readableBytes ^ByteBuf msg)))
        (.write ctx msg promise)))))

(defn pipeline-initializer [pipeline-builder]
  (proxy [ChannelInitializer] []
    (initChannel [^Channel ch]
      (pipeline-builder ^ChannelPipeline (.pipeline ch)))))

(defn remove-if-present [^ChannelPipeline pipeline ^Class handler]
  (when (some? (.get pipeline handler))
    (.remove pipeline handler))
  pipeline)

(defn instrument!
  [stream]
  (if-let [^Channel ch (->> stream meta :aleph/channel)]
    (do
      (safe-execute ch
        (let [pipeline (.pipeline ch)]
          (when (and
                  (.isActive ch)
                  (nil? (.get pipeline "bandwidth-tracker")))
            (.addFirst pipeline "bandwidth-tracker" (bandwidth-tracker ch)))))
      true)
    false))

(defn coerce-log-level [level]
  (if (instance? LogLevel level)
    level
    (let [netty-level (case level
                        :trace LogLevel/TRACE
                        :debug LogLevel/DEBUG
                        :info LogLevel/INFO
                        :warn LogLevel/WARN
                        :error LogLevel/ERROR
                        nil)]
      (when (nil? netty-level)
        (throw (IllegalArgumentException.
                (str "unknown log level given: " level))))
      netty-level)))

(defn activity-logger
  ([level]
   (LoggingHandler. ^LogLevel (coerce-log-level level)))
  ([^String name level]
   (LoggingHandler. name ^LogLevel (coerce-log-level level))))

;;;

(defn self-signed-ssl-context
  "A self-signed SSL context for servers."
  []
  (let [cert (SelfSignedCertificate.)]
    (.build (SslContextBuilder/forServer (.certificate cert) (.privateKey cert)))))

(defn insecure-ssl-client-context []
  (-> (SslContextBuilder/forClient)
      (.trustManager InsecureTrustManagerFactory/INSTANCE)
      .build))

(defn- check-ssl-args
  [private-key certificate-chain]
  (when-not
    (or (and (instance? File private-key) (instance? File certificate-chain))
      (and (instance? InputStream private-key) (instance? InputStream certificate-chain))
      (and (instance? PrivateKey private-key) (instance? (class (into-array X509Certificate [])) certificate-chain)))
    (throw (IllegalArgumentException. "ssl-client-context arguments invalid"))))

(set! *warn-on-reflection* false)

(defn ssl-client-context
  "Creates a new client SSL context.

  Keyword arguments are:

  |:---|:----
  | `private-key` | A `java.io.File`, `java.io.InputStream`, or `java.security.PrivateKey` containing the client-side private key.
  | `certificate-chain` | A `java.io.File`, `java.io.InputStream`, or array of `java.security.cert.X509Certificate` containing the client's certificate chain.
  | `private-key-password` | A string, the private key's password (optional).
  | `trust-store` | A `java.io.File`, `java.io.InputStream`, array of `java.security.cert.X509Certificate`, or a `javax.net.ssl.TrustManagerFactory` to initialize the context's trust manager.

  Note that if specified, the types of `private-key` and `certificate-chain` must be
  \"compatible\": either both input streams, both files, or a private key and an array
  of certificates."
  ([] (ssl-client-context {}))
  ([{:keys [private-key private-key-password certificate-chain trust-store]}]
    (-> (SslContextBuilder/forClient)
      (#(if (and private-key certificate-chain)
          (do
            (check-ssl-args private-key certificate-chain)
            (if (instance? (class (into-array X509Certificate [])) certificate-chain)
              (.keyManager %
                private-key
                private-key-password
                certificate-chain)
              (.keyManager %
                certificate-chain
                private-key
                private-key-password)))
          %))
      (#(if trust-store
          (.trustManager % trust-store)
          %))
      .build)))

(set! *warn-on-reflection* true)

(defn channel-ssl-session [^Channel ch]
  (some-> ch
          ^ChannelPipeline (.pipeline)
          ^SslHandler (.get SslHandler)
          .engine
          .getSession))
;;;

(defprotocol AlephServer
  (port [_] "Returns the port the server is listening on.")
  (wait-for-close [_] "Blocks until the server has been closed."))

(defn epoll-available? []
  (Epoll/isAvailable))

(defn get-default-event-loop-threads
  "Determines the default number of threads to use for a Netty EventLoopGroup.
   This mimics the default used by Netty as of version 4.1."
  []
  (let [cpu-count (->> (Runtime/getRuntime) (.availableProcessors))]
    (max 1 (SystemPropertyUtil/getInt "io.netty.eventLoopThreads" (* cpu-count 2)))))

(defn ^ThreadFactory enumerating-thread-factory [prefix daemon?]
  (let [num-threads (atom 0)]
    (e/thread-factory
     #(str prefix "-" (swap! num-threads inc))
     (deliver (promise) nil)
     nil
     daemon?)))

(def ^String client-event-thread-pool-name "aleph-netty-client-event-pool")

(def epoll-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (EpollEventLoopGroup. (long thread-count) thread-factory))))

(def nio-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (NioEventLoopGroup. (long thread-count) thread-factory))))

(defn convert-address-types [address-types]
  (case address-types
    :ipv4-only ResolvedAddressTypes/IPV4_ONLY
    :ipv6-only ResolvedAddressTypes/IPV6_ONLY
    :ipv4-preferred ResolvedAddressTypes/IPV4_PREFERRED
    :ipv6-preferred ResolvedAddressTypes/IPV6_PREFERRED))

(def dns-default-port 53)

(defn dns-name-servers-provider [servers]
  (let [addresses (->> servers
                    (map (fn [server]
                           (cond
                             (instance? InetSocketAddress server)
                             server

                             (string? server)
                             (let [^URI uri (URI. (str "dns://" server))
                                   port (.getPort uri)
                                   port' (int (if (= -1 port) dns-default-port port))]
                               (InetSocketAddress. (.getHost uri) port'))

                             :else
                             (throw
                               (IllegalArgumentException.
                                 (format "Don't know how to create InetSocketAddress from '%s'"
                                   server)))))))]
    (if (= 1 (count addresses))
      (SingletonDnsServerAddressStreamProvider. (first addresses))
      (SequentialDnsServerAddressStreamProvider. ^Iterable addresses))))

(defn dns-resolver-group-builder
  "Creates an instance of DnsAddressResolverGroupBuilder that is used to configure and 
initialize an DnsAddressResolverGroup instance.

   DNS options are a map of:

   |:--- |:---
   | `max-payload-size` | sets capacity of the datagram packet buffer (in bytes), defaults to `4096`
   | `max-queries-per-resolve` | sets the maximum allowed number of DNS queries to send when resolving a host name, defaults to `16`
   | `address-types` | sets the list of the protocol families of the address resolved, should be one of `:ipv4-only`, `:ipv4-preferred`, `:ipv6-only`, `:ipv4-preferred`  (calculated automatically based on ipv4/ipv6 support when not set explicitly)
   | `query-timeout` | sets the timeout of each DNS query performed by this resolver (in milliseconds), defaults to `5000`
   | `min-ttl` | sets minimum TTL of the cached DNS resource records (in seconds), defaults to `0`
   | `max-ttl` | sets maximum TTL of the cached DNS resource records (in seconds), defaults to `Integer/MAX_VALUE` (the resolver will respect the TTL from the DNS)
   | `negative-ttl` | sets the TTL of the cache for the failed DNS queries (in seconds)
   | `trace-enabled?` | if set to `true`, the resolver generates the detailed trace information in an exception message, defaults to `false`
   | `opt-resources-enabled?` | if set to `true`, enables the automatic inclusion of a optional records that tries to give the remote DNS server a hint about how much data the resolver can read per response, defaults to `true`
   | `search-domains` | sets the list of search domains of the resolver, when not given the default list is used (platform dependent)
   | `ndots` | sets the number of dots which must appear in a name before an initial absolute query is made, defaults to `-1`
   | `decode-idn?` | set if domain / host names should be decoded to unicode when received, defaults to `true`
   | `recursion-desired?` | if set to `true`, the resolver sends a DNS query with the RD (recursion desired) flag set, defaults to `true`
   | `name-servers` | optional list of DNS server addresses, automatically discovered when not set (platform dependent)
   | `epoll?` | if `true`, uses `epoll` when available, defaults to `false`"
  [{:keys [max-payload-size
           max-queries-per-resolve
           address-types
           query-timeout
           min-ttl
           max-ttl
           negative-ttl
           trace-enabled?
           opt-resources-enabled?
           search-domains
           ndots
           decode-idn?
           recursion-desired?
           name-servers
           epoll?]
    :or {max-payload-size 4096
         max-queries-per-resolve 16
         query-timeout 5000
         min-ttl 0
         max-ttl Integer/MAX_VALUE
         trace-enabled? false
         opt-resources-enabled? true
         ndots -1
         decode-idn? true
         recursion-desired? true
         epoll? false}}]
  (let [^Class
        channel-type (if (and epoll? (epoll-available?))
                       EpollDatagramChannel
                       NioDatagramChannel)]
    (cond-> (doto (DnsNameResolverBuilder.)
              (.channelType channel-type)
              (.maxPayloadSize max-payload-size)
              (.maxQueriesPerResolve max-queries-per-resolve)
              (.queryTimeoutMillis query-timeout)
              (.ttl min-ttl max-ttl)
              (.traceEnabled trace-enabled?)
              (.optResourceEnabled opt-resources-enabled?)
              (.ndots ndots)
              (.decodeIdn decode-idn?)
              (.recursionDesired recursion-desired?))

      (some? address-types)
      (.resolvedAddressTypes (convert-address-types address-types))

      (some? negative-ttl)
      (.negativeTtl negative-ttl)

      (and (some? search-domains)
           (not (empty? search-domains)))
      (.searchDomains search-domains)

      (and (some? name-servers)
           (not (empty? name-servers)))
      (.nameServerProvider ^DnsServerAddressStreamProvider
                           (dns-name-servers-provider name-servers)))))

(defn dns-resolver-group
  "Creates an instance of DnsAddressResolverGroup that might be set as a resolver to
 Bootstrap. The supported options are the same as to `dns-resolver-group-builder`."
  [dns-options]
  (DnsAddressResolverGroup. (dns-resolver-group-builder dns-options)))

(defn create-client
  ([pipeline-builder
    ssl-context
    bootstrap-transform
    remote-address
    local-address
    epoll?]
    (create-client pipeline-builder
      ssl-context
      bootstrap-transform
      remote-address
      local-address
      epoll?
      nil))
  ([pipeline-builder
    ^SslContext ssl-context
    bootstrap-transform
    ^SocketAddress remote-address
    ^SocketAddress local-address
    epoll?
    name-resolver]
    (let [^Class
          channel (if (and epoll? (epoll-available?))
                    EpollSocketChannel
                    NioSocketChannel)

          pipeline-builder (if ssl-context
                             (fn [^ChannelPipeline p]
                               (.addLast p "ssl-handler"
                                 (.newHandler ^SslContext ssl-context
                                   (-> p .channel .alloc)
                                   (.getHostName ^InetSocketAddress remote-address)
                                   (.getPort ^InetSocketAddress remote-address)))
                               (pipeline-builder p))
                             pipeline-builder)]
      (try
        (let [client-group (if (and epoll? (epoll-available?))
                             @epoll-client-group
                             @nio-client-group)
              resolver' (when (some? name-resolver)
                          (cond
                            (= :default name-resolver) nil
                            (= :noop name-resolver) NoopAddressResolverGroup/INSTANCE
                            (instance? AddressResolverGroup name-resolver) name-resolver))
              b (doto (Bootstrap.)
                  (.option ChannelOption/SO_REUSEADDR true)
                  (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                  (.group client-group)
                  (.channel channel)
                  (.handler (pipeline-initializer pipeline-builder))
                  (.resolver resolver')
                  bootstrap-transform)

              f (if local-address
                  (.connect b remote-address local-address)
                  (.connect b remote-address))]

          (d/chain' (wrap-future f)
            (fn [_]
              (let [ch (.channel ^ChannelFuture f)]
                ch))))))))

(defn start-server
  [pipeline-builder
   ^SslContext ssl-context
   bootstrap-transform
   on-close
   ^SocketAddress socket-address
   epoll?]
  (let [num-cores      (.availableProcessors (Runtime/getRuntime))
        num-threads    (* 2 num-cores)
        thread-factory (enumerating-thread-factory "aleph-netty-server-event-pool" false)
        closed?        (atom false)

        ^EventLoopGroup group
        (if (and epoll? (epoll-available?))
          (EpollEventLoopGroup. num-threads thread-factory)
          (NioEventLoopGroup. num-threads thread-factory))

        ^Class channel
        (if (and epoll? (epoll-available?))
          EpollServerSocketChannel
          NioServerSocketChannel)

        ;; todo(kachayev): this one should be reimplemented after
        ;;                 KQueue transport is merged into master
        transport
        (if (and epoll? (epoll-available?)) :epoll :nio)

        pipeline-builder
        (if ssl-context
          (fn [^ChannelPipeline p]
            (.addLast p "ssl-handler"
              (.newHandler ssl-context
                (-> p .channel .alloc)))
            (pipeline-builder p))
          pipeline-builder)]

    (try
      (let [b (doto (ServerBootstrap.)
                (.option ChannelOption/SO_BACKLOG (int 1024))
                (.option ChannelOption/SO_REUSEADDR true)
                (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                (.group group)
                (.channel channel)
                (.childHandler (pipeline-initializer pipeline-builder))
                (.childOption ChannelOption/SO_REUSEADDR true)
                (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                bootstrap-transform)

            ^ServerSocketChannel
            ch (-> b (.bind socket-address) .sync .channel)]
        (reify
          java.io.Closeable
          (close [_]
            (when (compare-and-set! closed? false true)
              (-> ch .close .sync)
              (-> group .shutdownGracefully)
              (when on-close
                (d/chain'
                 (wrap-future (.terminationFuture group))
                 (fn [_] (on-close))))))
          Object
          (toString [_]
            (format "AlephServer[channel:%s, transport:%s]" ch transport))
          AlephServer
          (port [_]
            (-> ch .localAddress .getPort))
          (wait-for-close [_]
            (-> ch .closeFuture .await)
            (-> group .terminationFuture .await)
            nil)))

      (catch Exception e
        @(.shutdownGracefully group)
        (throw e)))))
