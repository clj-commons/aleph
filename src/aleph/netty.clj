(ns aleph.netty
  (:refer-clojure :exclude [flush])
  (:require
    [clj-commons.byte-streams :as bs]
    [clj-commons.primitive-math :as p]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.executor :as e]
    [manifold.stream :as s]
    [manifold.stream.core :as manifold]
    [potemkin :refer [doary doit]])
  (:import
    (aleph.http AlephChannelInitializer)
    (clojure.lang DynamicClassLoader)
    (io.netty.bootstrap
      Bootstrap
      ServerBootstrap)
    (io.netty.buffer
      ByteBuf
      Unpooled)
    (io.netty.channel
      Channel
      ChannelFuture
      ChannelHandler
      ChannelHandlerContext
      ChannelInboundHandler
      ChannelInitializer
      ChannelOption
      ChannelOutboundHandler
      ChannelOutboundInvoker
      ChannelPipeline
      EventLoopGroup
      FileRegion)
    (io.netty.channel.epoll
      Epoll
      EpollDatagramChannel
      EpollEventLoopGroup
      EpollServerSocketChannel
      EpollSocketChannel)
    (io.netty.channel.group
      ChannelGroup
      DefaultChannelGroup)
    (io.netty.channel.kqueue
      KQueue
      KQueueDatagramChannel
      KQueueEventLoopGroup
      KQueueServerSocketChannel
      KQueueSocketChannel)
    (io.netty.channel.nio NioEventLoopGroup)
    (io.netty.channel.socket
      ChannelInputShutdownReadComplete
      ServerSocketChannel)
    (io.netty.channel.socket.nio
      NioDatagramChannel
      NioServerSocketChannel
      NioSocketChannel)
    (io.netty.handler.codec
      CharSequenceValueConverter
      DecoderException)
    (io.netty.handler.codec.http2 Http2StreamChannel)
    (io.netty.handler.logging
      LogLevel
      LoggingHandler)
    (io.netty.handler.ssl
      ApplicationProtocolConfig
      ClientAuth
      SslContext
      SslContextBuilder
      SslHandler
      SslProvider)
    (io.netty.handler.ssl.util
      InsecureTrustManagerFactory
      SelfSignedCertificate)
    (io.netty.incubator.channel.uring
      IOUring
      IOUringDatagramChannel
      IOUringEventLoopGroup
      IOUringServerSocketChannel
      IOUringSocketChannel)
    (io.netty.resolver
      AddressResolverGroup
      NoopAddressResolverGroup
      ResolvedAddressTypes)
    (io.netty.resolver.dns
      DnsAddressResolverGroup
      DnsNameResolverBuilder
      DnsServerAddressStreamProvider
      SequentialDnsServerAddressStreamProvider
      SingletonDnsServerAddressStreamProvider)
    (io.netty.util
      Attribute
      AttributeKey
      ResourceLeakDetector
      ResourceLeakDetector$Level)
    (io.netty.util.concurrent
      FastThreadLocalThread
      Future
      GenericFutureListener
      GlobalEventExecutor)
    (io.netty.util.internal
      StringUtil
      SystemPropertyUtil)
    (io.netty.util.internal.logging
      InternalLoggerFactory
      JdkLoggerFactory
      Log4J2LoggerFactory
      Log4JLoggerFactory
      Slf4JLoggerFactory)
    (java.io
      Closeable
      File
      IOException
      InputStream)
    (java.net
      InetSocketAddress
      SocketAddress
      URI)
    (java.nio ByteBuffer)
    (java.security PrivateKey)
    (java.security.cert X509Certificate)
    (java.util.concurrent
      CancellationException
      ConcurrentHashMap
      ScheduledFuture
      ThreadFactory
      TimeUnit)
    (java.util.concurrent.atomic
      AtomicLong)
    (javax.net.ssl
      SSLHandshakeException
      TrustManager
      TrustManagerFactory)))

(set! *warn-on-reflection* true)

(def ^:no-doc ^CharSequenceValueConverter char-seq-val-converter CharSequenceValueConverter/INSTANCE)

;;;

(definline release
  "Decreases the reference count by 1 and deallocates this object if the reference count reaches at 0."
  [x]
  `(io.netty.util.ReferenceCountUtil/release ~x))

(definline acquire
  "Increases the reference count by 1."
  [x]
  `(io.netty.util.ReferenceCountUtil/retain ~x))

(defn ^:no-doc leak-detector-level! [level]
  (ResourceLeakDetector/setLevel
    (case level
      :disabled ResourceLeakDetector$Level/DISABLED
      :simple ResourceLeakDetector$Level/SIMPLE
      :advanced ResourceLeakDetector$Level/ADVANCED
      :paranoid ResourceLeakDetector$Level/PARANOID)))

(defn set-logger!
  "Changes the default logger factory.
  The parameter can be either `:log4j`, `:log4j2`, `:slf4j` or `:jdk`."
  [logger]
  (InternalLoggerFactory/setDefaultFactory
    (case logger
      :log4j Log4JLoggerFactory/INSTANCE
      :log4j2 Log4J2LoggerFactory/INSTANCE
      :slf4j Slf4JLoggerFactory/INSTANCE
      :jdk JdkLoggerFactory/INSTANCE)))

;;;

(defn ^:no-doc channel-server-name [^Channel ch]
  (some-> ch ^InetSocketAddress (.localAddress) (.getHostName)))

(defn ^:no-doc channel-server-port [^Channel ch]
  (some-> ch ^InetSocketAddress (.localAddress) (.getPort)))

(defn ^:no-doc channel-remote-address [^Channel ch]
  (some-> ch ^InetSocketAddress (.remoteAddress) (.getAddress) (.getHostAddress)))

;;; Defaults defined here since they are not publically exposed by Netty

(def ^:const ^:no-doc default-shutdown-timeout
  "Default timeout in seconds to wait for graceful shutdown complete"
  15)

(def ^:const ^:no-doc byte-array-class (Class/forName "[B"))

#_
(defn ^:no-doc buf->array [^ByteBuf buf]
  (let [dst (ByteBuffer/allocate (.readableBytes buf))]
    (doary [^ByteBuffer buf (.nioBuffers buf)]
           (.put dst buf))
    (.array dst)))

(defn ^:no-doc buf->array [^ByteBuf buf]
  (if (.hasArray buf)
    (-> buf
        (.array)
        (java.util.Arrays/copyOfRange (.readerIndex buf) (.writerIndex buf)))
    (let [dst (ByteBuffer/allocate (.readableBytes buf))]
      (doary [^ByteBuffer buf (.nioBuffers buf)]
             (.put dst buf))
      (.array dst))))



(defn ^:no-doc release-buf->array [^ByteBuf buf]
  (try
    (buf->array buf)
    (finally
      (release buf))))

(defn ^:no-doc bufs->array [bufs]
  (let [bufs' (mapcat #(.nioBuffers ^ByteBuf %) bufs)
        dst (ByteBuffer/allocate
              (loop [cnt 0, s bufs']
                (if (empty? s)
                  cnt
                  (recur (p/+ cnt (.remaining ^ByteBuffer (first s))) (rest s)))))]
    (doit [^ByteBuffer buf bufs']
          (.put dst buf))
    (.array dst)))

(bs/def-conversion ^{:cost 1} [ByteBuf byte-array-class]
  [buf options]
  (let [ary (buf->array buf)]
    (release buf)
    ary))

(bs/def-conversion ^{:cost 0} [ByteBuffer ByteBuf]
  [buf options]
  (Unpooled/wrappedBuffer buf))

(declare ^:no-doc allocate)

(let [charset (java.nio.charset.Charset/forName "UTF-8")]
  (defn append-to-buf!
    "Appends `x` to an existing `io.netty.buffer.ByteBuf`."
    [^ByteBuf buf x]
    (cond
      (instance? byte-array-class x)
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
    "Converts `x` into a `io.netty.buffer.ByteBuf`."
    ([x]
     (cond
       (nil? x)
       Unpooled/EMPTY_BUFFER

       (instance? byte-array-class x)
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

(defn to-byte-buf-stream
  "Converts `x` into a manifold stream of `io.netty.ByteBuf` of `chunk-size`."
  [x chunk-size]
  (->> (bs/convert x (bs/stream-of ByteBuf) {:chunk-size chunk-size})
       (s/onto nil)))

(defn ^:no-doc ensure-dynamic-classloader
  "Ensure the context class loader has a valid loader chain to
   prevent `ClassNotFoundException`.
   https://github.com/clj-commons/aleph/issues/603."
  []
  (let [thread (Thread/currentThread)
        context-class-loader (.getContextClassLoader thread)
        compiler-class-loader (.getClassLoader clojure.lang.Compiler)]
    (when-not (instance? DynamicClassLoader context-class-loader)
      (.setContextClassLoader
        thread
        (DynamicClassLoader. (or context-class-loader
                                 compiler-class-loader))))))

(defn- operation-complete [^Future f d]
  (cond
    (.isSuccess f)
    (d/success! d (.getNow f))

    (.isCancelled f)
    (d/error! d (CancellationException. "future is cancelled."))

    (some? (.cause f))
    (d/error! d (.cause f))

    :else
    (d/error! d (IllegalStateException. "future in unknown state"))))

(defn ^:no-doc wrap-future
  [^Future f]
  (when f
    (let [d (d/deferred nil)]
      (if (.isDone f)
        (operation-complete f d)
        (.addListener f
                      (reify GenericFutureListener
                        (operationComplete [_ _]
                          (ensure-dynamic-classloader)
                          (operation-complete f d)))))
      d)))

(defn ^:no-doc allocate [x]
  (if (instance? Channel x)
    (-> ^Channel x .alloc .ioBuffer)
    (-> ^ChannelHandlerContext x .alloc .ioBuffer)))

;; TODO: convert to use ChannelOutboundInvoker
(defn ^:no-doc write [x msg]
  (if (instance? Channel x)
    (.write ^Channel x msg (.voidPromise ^Channel x))
    (.write ^ChannelHandlerContext x msg (.voidPromise ^ChannelHandlerContext x)))
  nil)

;; TODO: convert to use ChannelOutboundInvoker
(defn ^:no-doc write-and-flush
  [x msg]
  (if (instance? Channel x)
    (.writeAndFlush ^Channel x msg)
    (.writeAndFlush ^ChannelHandlerContext x msg)))

;; TODO: convert to use ChannelOutboundInvoker
(defn ^:no-doc flush [x]
  (if (instance? Channel x)
    (.flush ^Channel x)
    (.flush ^ChannelHandlerContext x)))

;; TODO: inline or macroize
(defn ^:no-doc close
  [^ChannelOutboundInvoker x]
  (.close x))

(defn ^:no-doc channel
  ^Channel [x]
  (if (instance? Channel x)
    x
    (.channel ^ChannelHandlerContext x)))

(defn ^:no-doc ^ChannelGroup make-channel-group
  "Create a channel group which can be used to perform channel operations on
   several channels at once."
  []
  (DefaultChannelGroup. GlobalEventExecutor/INSTANCE))

(defmacro ^:no-doc safe-execute
  "Executes the body on the event-loop (an executor service) associated with
   the Netty channel or context.

   Executes immediately if current thread is in the event loop. Otherwise,
   returns a deferred that will hold the result once done."
  [ch & body]
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

(defn ^:no-doc put!
  "Netty-aware put! that can apply backpressure to Netty channels if necessary"
  [^Channel ch s msg]
  (let [d (s/put! s msg)]
    (d/success-error-unrealized d

      val (if val
            true
            (do
              (release msg)
              (.close ch)
              false))

      err (do
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

(defn ^:no-doc attribute [s]
  (AttributeKey/valueOf (name s)))

(defn ^:no-doc get-attribute [ch attr]
  (-> ch channel ^Attribute (.attr attr) .get))

(defn ^:no-doc set-attribute [ch attr val]
  (-> ch channel ^Attribute (.attr attr) (.set val)))

;;;

(def ^:no-doc ^ConcurrentHashMap channel-inbound-counter (ConcurrentHashMap.))
(def ^:no-doc ^ConcurrentHashMap channel-outbound-counter (ConcurrentHashMap.))
(def ^:no-doc ^ConcurrentHashMap channel-inbound-throughput (ConcurrentHashMap.))
(def ^:no-doc ^ConcurrentHashMap channel-outbound-throughput (ConcurrentHashMap.))

(defn- ^:no-doc connection-stats [^Channel ch inbound?]
  (merge
    {:local-address  (str (.localAddress ch))
     :remote-address (str (.remoteAddress ch))
     :writable?      (.isWritable ch)
     :readable?      (-> ch .config .isAutoRead)
     :closed?        (not (.isActive ch))}
    (let [^ConcurrentHashMap throughput (if inbound?
                                          channel-inbound-throughput
                                          channel-outbound-throughput)]
      (when-let [^AtomicLong throughput (.get throughput ch)]
        {:throughput (.get throughput)}))))

(def ^:no-doc sink-close-marker ::sink-close)

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

(defn ^:no-doc sink
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

(defn ^:no-doc source
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

(defn ^:no-doc buffered-source
  "A buffered Manifold stream with the originating Channel attached to the
   stream's metadata"
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

(defmacro ^:no-doc channel-handler
  "Mimics the ChannelDuplexHandler in providing simple defaults or passing along all events"
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

(defmacro ^:no-doc channel-inbound-handler
  "Effectively a macro version of ChannelInboundHandlerAdapter.

   Default behavior is to pass along all events. Default handler
   added/removed behavior is NOOP."
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
               (.fireExceptionCaught ctx# ^Throwable cause#))))
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

(defmacro ^:no-doc channel-outbound-handler
  "Effectively a macro version of ChannelOutboundHandlerAdapter.

   Default behavior is to forward calls to the context. Default handler
   added/removed behavior is NOOP."
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


(defn ^:no-doc ^ChannelHandler bandwidth-tracker [^Channel ch]
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

(defn ^:no-doc channel-tracking-handler
  "Yields an inbound handler, ready to be added to a pipeline,
   which keeps track of requests in a provided channel group.
   The channel-group can be created via `make-channel-group`."
  [^ChannelGroup group]
  (channel-inbound-handler
    :channel-active
    ([_ ctx]
     (.add group (channel ctx))
     (.fireChannelActive ctx))))

(defn pipeline-initializer
  "Returns a ChannelInitializer which builds the pipeline.

   `pipeline-builder` is a 1-arity fn that takes a ChannelPipeline and
   configures it."
  ^ChannelInitializer
  [pipeline-builder]
  (AlephChannelInitializer. #(pipeline-builder (.pipeline ^Channel %))))

(defn remove-if-present
  "Convenience function to remove a handler from a netty pipeline."
  [^ChannelPipeline pipeline ^Class handler]
  (when (some? (.get pipeline handler))
    (.remove pipeline handler))
  pipeline)

(defn ^:no-doc instrument!
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

(defn ^:no-doc pause-handler
  "Returns a handler that stores all messages until removed from the pipeline."
  []
  (let [msgs (atom [])
        fire-buffered-msgs (fn [^ChannelHandlerContext ctx]
                             (log/debug "pause-handler - firing buffered msgs")
                             (run! #(.fireChannelRead ctx %) @msgs)
                             (.fireChannelReadComplete ctx))]
    (channel-inbound-handler
      {:channel-read
       ([_ ctx msg]
        (log/trace "pause-handler buffering msg" msg)
        (swap! msgs conj msg))

       :handler-removed
       ([_ ctx]
        (fire-buffered-msgs ctx))

       :channel-inactive
       ([_ ctx]
        (fire-buffered-msgs ctx)
        (.fireChannelInactive ctx))

       :user-event-triggered
       ([_ ctx evt]
        (when (instance? ChannelInputShutdownReadComplete evt)
          (fire-buffered-msgs ctx))
        (.fireUserEventTriggered ctx evt))})))

(defn ^:no-doc coerce-log-level
  "Returns a netty LogLevel. Accepts either a keyword or a LogLevel."
  [level]
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

(defn ^:no-doc activity-logger
  ([level]
   (LoggingHandler. ^LogLevel (coerce-log-level level)))
  ([^String name level]
   (LoggingHandler. name ^LogLevel (coerce-log-level level))))

;;;

(defn ^:no-doc coerce-ssl-provider ^SslProvider [provider]
  (case provider
    :jdk SslProvider/JDK
    :openssl SslProvider/OPENSSL
    :openssl-refcnt SslProvider/OPENSSL_REFCNT))

(let [cert-array-class (class (into-array X509Certificate []))]
  (defn- add-ssl-trust-manager! ^SslContextBuilder [^SslContextBuilder builder trust-store]
    (cond (instance? File trust-store)
          (.trustManager builder ^File trust-store)
          (instance? InputStream trust-store)
          (.trustManager builder ^InputStream trust-store)
          (instance? TrustManager trust-store)
          (.trustManager builder ^TrustManager trust-store)
          (instance? TrustManagerFactory trust-store)
          (.trustManager builder ^TrustManagerFactory trust-store)
          (instance? cert-array-class trust-store)
          (.trustManager builder ^"[Ljava.security.cert.X509Certificate;" trust-store)
          (sequential? trust-store)
          (let [^"[Ljava.security.cert.X509Certificate;" trust-store' (into-array X509Certificate trust-store)]
            (.trustManager builder trust-store'))
          :else
          (throw
            (IllegalArgumentException.
              "ssl context arguments invalid"))))

  (defn ssl-client-context
    "Creates a new client SSL context.
     Keyword arguments are:

     Param key                | Description
     | ---                    | ---
     | `private-key`          | a `java.io.File`, `java.io.InputStream`, or `java.security.PrivateKey` containing the client-side private key.
     | `certificate-chain`    | a `java.io.File`, `java.io.InputStream`, sequence of `java.security.cert.X509Certificate`, or array of `java.security.cert.X509Certificate` containing the client's certificate chain.
     | `private-key-password` | a string, the private key's password (optional).
     | `trust-store`          | a `java.io.File`, `java.io.InputStream`, array of `java.security.cert.X509Certificate`, `javax.net.ssl.TrustManager`, or a `javax.net.ssl.TrustManagerFactory` to initialize the context's trust manager.
     | `ssl-provider`         | `SslContext` implementation to use, one of `:jdk`, `:openssl` or `:openssl-refcnt`. Note, that when using OpenSSL-based implementations, the library should be installed and linked properly.
     | `ciphers`              | a sequence of strings, the cipher suites to enable, in the order of preference.
     | `protocols`            | a sequence of strings, the TLS protocol versions to enable.
     | `session-cache-size`   | the size of the cache used for storing SSL session objects.
     | `session-timeout`      | the timeout for the cached SSL session objects, in seconds.
     | `application-protocol-config` | an `ApplicationProtocolConfig` instance to configure ALPN.
     Note that if specified, the types of `private-key` and `certificate-chain` must be \"compatible\": either both input streams, both files, or a private key and an array of certificates."
    (^SslContext
     []
     (ssl-client-context {}))
    (^SslContext
     [{:keys [private-key
              ^String private-key-password
              certificate-chain
              trust-store
              ssl-provider
              ^Iterable ciphers
              protocols
              ^long session-cache-size
              ^long session-timeout
              ^ApplicationProtocolConfig application-protocol-config]}]

     (let [^SslContextBuilder builder (SslContextBuilder/forClient)]
       (when (or private-key certificate-chain)
         (cond
           (and (instance? File private-key)
                (instance? File certificate-chain))
           (.keyManager builder
                        ^File certificate-chain
                        ^File private-key
                        private-key-password)

           (and (instance? InputStream private-key)
                (instance? InputStream certificate-chain))
           (.keyManager builder
                        ^InputStream certificate-chain
                        ^InputStream private-key
                        private-key-password)

           (and (instance? PrivateKey private-key)
                (instance? cert-array-class certificate-chain))
           (.keyManager builder
                        ^PrivateKey private-key
                        private-key-password
                        ^"[Ljava.security.cert.X509Certificate;" certificate-chain)

           (and (instance? PrivateKey private-key)
                (sequential? certificate-chain))
           (let [^"[Ljava.security.cert.X509Certificate;" certificate-chain' (into-array X509Certificate certificate-chain)]
             (.keyManager builder
                          ^PrivateKey private-key
                          private-key-password
                          certificate-chain'))

           :else
           (throw
             (IllegalArgumentException. "ssl context arguments invalid"))))

       (cond-> builder
               (some? trust-store)
               (add-ssl-trust-manager! trust-store)

               (some? ssl-provider)
               (.sslProvider (coerce-ssl-provider ssl-provider))

               (some? ciphers)
               (.ciphers ciphers)

               (some? protocols)
               (.protocols ^"[Ljava.lang.String;" (into-array String protocols))

               (some? session-cache-size)
               (.sessionCacheSize session-cache-size)

               (some? session-timeout)
               (.sessionTimeout session-timeout)

               (some? application-protocol-config)
               (.applicationProtocolConfig application-protocol-config))

       (.build builder))))

  (defn ssl-server-context
    "Creates a new server SSL context.
     Keyword arguments are:

     Param key                | Description
     | ---                    | ---
     | `private-key`          | a `java.io.File`, `java.io.InputStream`, or `java.security.PrivateKey` containing the server-side private key.
     | `certificate-chain`    | a `java.io.File`, `java.io.InputStream`, or array of `java.security.cert.X509Certificate` containing the server's certificate chain.
     | `private-key-password` | a string, the private key's password (optional).
     | `trust-store`          | a `java.io.File`, `java.io.InputStream`, sequence of `java.security.cert.X509Certificate`,  array of `java.security.cert.X509Certificate`, `javax.net.ssl.TrustManager`, or a `javax.net.ssl.TrustManagerFactory` to initialize the context's trust manager.
     | `ssl-provider`         | `SslContext` implementation to use, on of `:jdk`, `:openssl` or `:openssl-refcnt`. Note, that when using OpenSSL based implementations, the library should be installed and linked properly.
     | `ciphers`              | a sequence of strings, the cipher suites to enable, in the order of preference.
     | `protocols`            | a sequence of strings, the TLS protocol versions to enable.
     | `session-cache-size`   | the size of the cache used for storing SSL session objects.
     | `session-timeout`      | the timeout for the cached SSL session objects, in seconds.
     | `start-tls`            | if the first write request shouldn't be encrypted.
     | `client-auth`          | the client authentication mode, one of `:none`, `:optional` or `:require`.
     | `application-protocol-config` | an `ApplicationProtocolConfig` instance to configure ALPN.

     Note that if specified, the types of `private-key` and `certificate-chain` must be \"compatible\": either
     both input streams, both files, or a private key and an array of certificates."
    (^SslContext
     []
     (ssl-server-context {}))
    (^SslContext
     [{:keys [private-key
              ^String private-key-password
              certificate-chain
              trust-store
              ssl-provider
              ^Iterable ciphers
              protocols
              ^long session-cache-size
              ^long session-timeout
              start-tls
              client-auth
              ^ApplicationProtocolConfig application-protocol-config]}]
     (let [^SslContextBuilder
           b (cond (and (instance? String private-key)
                        (instance? String certificate-chain))
                   (SslContextBuilder/forServer ^File (File. ^String certificate-chain)
                                                ^File (File. ^String private-key)
                                                private-key-password)
                   (and (instance? File private-key)
                        (instance? File certificate-chain))
                   (SslContextBuilder/forServer ^File certificate-chain
                                                ^File private-key
                                                private-key-password)
                   (and (instance? InputStream private-key)
                        (instance? InputStream certificate-chain))
                   (SslContextBuilder/forServer ^InputStream certificate-chain
                                                ^InputStream private-key
                                                private-key-password)
                   (and (instance? PrivateKey private-key)
                        (instance? cert-array-class certificate-chain))
                   (SslContextBuilder/forServer ^PrivateKey private-key
                                                private-key-password
                                                ^"[Ljava.security.cert.X509Certificate;" certificate-chain)
                   (and (instance? PrivateKey private-key)
                        (sequential? certificate-chain))
                   (let [^"[Ljava.security.cert.X509Certificate;" certificate-chain' (into-array X509Certificate certificate-chain)]
                     (SslContextBuilder/forServer ^PrivateKey private-key
                                                  private-key-password
                                                  certificate-chain'))
                   :else
                   (throw
                     (IllegalArgumentException.
                       "ssl context arguments invalid")))

           ^SslContextBuilder
           b (cond-> b
                     (some? trust-store)
                     (add-ssl-trust-manager! trust-store)

                     (some? ssl-provider)
                     (.sslProvider (coerce-ssl-provider ssl-provider))

                     (some? ciphers)
                     (.ciphers ciphers)


                     (some? protocols)
                     (.protocols ^"[Ljava.lang.String;" (into-array String protocols))


                     (some? session-cache-size)
                     (.sessionCacheSize session-cache-size)

                     (some? session-timeout)
                     (.sessionTimeout session-timeout)

                     (some? start-tls)
                     (.startTls (boolean start-tls))

                     (some? client-auth)
                     (.clientAuth (case client-auth
                                    :none ClientAuth/NONE
                                    :optional ClientAuth/OPTIONAL
                                    :require ClientAuth/REQUIRE))

                     (some? application-protocol-config)
                     (.applicationProtocolConfig application-protocol-config))]
       (.build b)))))

(defn self-signed-ssl-context
  "A self-signed SSL context for servers.

   Never use in production."
  ([] (self-signed-ssl-context {}))
  ([opts]
   (let [cert (SelfSignedCertificate.)]
     (ssl-server-context (assoc opts
                                :private-key (.privateKey cert)
                                :certificate-chain (.certificate cert))))))

(defn insecure-ssl-client-context
  "An insecure client SSL context."
  ([]
   (ssl-client-context {:trust-store InsecureTrustManagerFactory/INSTANCE}))
  ([opts]
   (ssl-client-context (assoc opts :trust-store InsecureTrustManagerFactory/INSTANCE))))

(defn- coerce-ssl-context [options->context ssl-context]
  (cond
    (instance? SslContext ssl-context)
    ssl-context

    ;; in future this option might be interesing
    ;; for turning application config (e.g. ALPN)
    ;; depending on the server's capabilities
    (instance? SslContextBuilder ssl-context)
    (.build ^SslContextBuilder ssl-context)

    (map? ssl-context)
    (options->context ssl-context)))

(def ^:no-doc coerce-ssl-server-context
  (partial coerce-ssl-context ssl-server-context))

(def ^:no-doc coerce-ssl-client-context
  (partial coerce-ssl-context ssl-client-context))

(defn ^:no-doc channel-ssl-session [^Channel ch]
  (some-> ch
          ^ChannelPipeline (.pipeline)
          ^SslHandler (.get SslHandler)
          .engine
          .getSession))

(defn ^:no-doc ssl-handshake-error? [^Throwable ex]
  (and (instance? DecoderException ex)
       (instance? SSLHandshakeException (.getCause ex))))

;;;

(defprotocol AlephServer
  (port [_] "Returns the port the server is listening on.")
  (wait-for-close [_] "Blocks until the server has been closed."))

(defn ^:no-doc epoll-available? []
  (Epoll/isAvailable))

(defn ^:no-doc kqueue-available? []
  (KQueue/isAvailable))

(defn ^:no-doc io-uring-available? []
  (IOUring/isAvailable))

(defn ^:no-doc determine-transport [transport epoll?]
  (or transport (if epoll? :epoll :nio)))

(defn- unavailability-cause [transport]
  (case transport
    :epoll [(Epoll/unavailabilityCause) "Epoll"]
    :kqueue [(KQueue/unavailabilityCause) "KQueue"]
    :io-uring [(IOUring/unavailabilityCause) "IO-Uring"]
    nil))

(defn ^:no-doc ensure-transport-available! [transport]
  (let [[cause transport-name] (unavailability-cause transport)]
    (when cause
      (throw (IllegalArgumentException.
               (str transport-name " transport requested but implementation not available. "
                    "See https://netty.io/wiki/native-transports.html on how to add the necessary "
                    "dependency for your platform.")
               cause)))))

(defn ^:no-doc ensure-epoll-available! []
  (ensure-transport-available! :epoll))

(defn ^:no-doc get-default-event-loop-threads
  "Determines the default number of threads to use for a Netty EventLoopGroup.
   This mimics the default used by Netty as of version 4.1."
  []
  (let [cpu-count (->> (Runtime/getRuntime) (.availableProcessors))]
    (max 1 (SystemPropertyUtil/getInt "io.netty.eventLoopThreads" (* cpu-count 2)))))

(defn ^:no-doc ^ThreadFactory enumerating-thread-factory [prefix daemon?]
  (let [num-threads (atom 0)]
    (e/thread-factory
      #(str prefix "-" (swap! num-threads inc))
      (deliver (promise) nil)
      nil
      daemon?
      (fn [group target name stack-size] (FastThreadLocalThread. group target name stack-size)))))

(def ^:no-doc ^String client-event-thread-pool-name "aleph-netty-client-event-pool")

(def ^:private epoll-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (EpollEventLoopGroup. (long thread-count) thread-factory))))

(def ^:private nio-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (NioEventLoopGroup. (long thread-count) thread-factory))))

(def ^:private kqueue-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (KQueueEventLoopGroup. (long thread-count) thread-factory))))

(def ^:private io-uring-client-group
  (delay
    (let [thread-count (get-default-event-loop-threads)
          thread-factory (enumerating-thread-factory client-event-thread-pool-name true)]
      (IOUringEventLoopGroup. (long thread-count) thread-factory))))

(defn ^:no-doc transport-client-group [transport]
  (case transport
    :epoll epoll-client-group
    :kqueue kqueue-client-group
    :io-uring io-uring-client-group
    :nio nio-client-group))

(defn ^:no-doc transport-event-loop-group [transport ^long num-threads ^ThreadFactory thread-factory]
  (case transport
    :epoll (EpollEventLoopGroup. num-threads thread-factory)
    :kqueue (KQueueEventLoopGroup. num-threads thread-factory)
    :io-uring (IOUringEventLoopGroup. num-threads thread-factory)
    :nio (NioEventLoopGroup. num-threads thread-factory)))

(defn ^:no-doc transport-server-channel-class [transport]
  (case transport
    :epoll EpollServerSocketChannel
    :kqueue KQueueServerSocketChannel
    :io-uring IOUringServerSocketChannel
    :nio NioServerSocketChannel))

(defn ^:no-doc convert-address-types [address-types]
  (case address-types
    :ipv4-only ResolvedAddressTypes/IPV4_ONLY
    :ipv6-only ResolvedAddressTypes/IPV6_ONLY
    :ipv4-preferred ResolvedAddressTypes/IPV4_PREFERRED
    :ipv6-preferred ResolvedAddressTypes/IPV6_PREFERRED))

(def ^:no-doc dns-default-port 53)

(defn ^:no-doc dns-name-servers-provider [servers]
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

(defn ^:no-doc transport-channel-type [transport]
  (case transport
    :epoll EpollDatagramChannel
    :kqueue KQueueDatagramChannel
    :io-uring IOUringDatagramChannel
    :nio NioDatagramChannel))

(defn- transport-channel-class [transport]
  (case transport
    :epoll EpollSocketChannel
    :kqueue KQueueSocketChannel
    :io-uring IOUringSocketChannel
    :nio NioSocketChannel))

(defn dns-resolver-group-builder
  "Creates an instance of DnsAddressResolverGroupBuilder that is used to configure and
initialize an DnsAddressResolverGroup instance.

   DNS options are a map of:

   Param key                   | Description
   | ---                       | ---
   | `max-payload-size`        | sets capacity of the datagram packet buffer (in bytes), defaults to `4096`
   | `max-queries-per-resolve` | sets the maximum allowed number of DNS queries to send when resolving a host name, defaults to `16`
   | `address-types`           | sets the list of the protocol families of the address resolved, should be one of `:ipv4-only`, `:ipv4-preferred`, `:ipv6-only`, `:ipv4-preferred`  (calculated automatically based on ipv4/ipv6 support when not set explicitly)
   | `query-timeout`           | sets the timeout of each DNS query performed by this resolver (in milliseconds), defaults to `5000`
   | `min-ttl`                 | sets minimum TTL of the cached DNS resource records (in seconds), defaults to `0`
   | `max-ttl`                 | sets maximum TTL of the cached DNS resource records (in seconds), defaults to `Integer/MAX_VALUE` (the resolver will respect the TTL from the DNS)
   | `negative-ttl`            | sets the TTL of the cache for the failed DNS queries (in seconds)
   | `trace-enabled?`          | if set to `true`, the resolver generates the detailed trace information in an exception message, defaults to `false`
   | `opt-resources-enabled?`  | if set to `true`, enables the automatic inclusion of a optional records that tries to give the remote DNS server a hint about how much data the resolver can read per response, defaults to `true`
   | `search-domains`          | sets the list of search domains of the resolver, when not given the default list is used (platform dependent)
   | `ndots`                   | sets the number of dots which must appear in a name before an initial absolute query is made, defaults to `-1`
   | `decode-idn?`             | set if domain / host names should be decoded to unicode when received, defaults to `true`
   | `recursion-desired?`      | if set to `true`, the resolver sends a DNS query with the RD (recursion desired) flag set, defaults to `true`
   | `name-servers`            | optional list of DNS server addresses, automatically discovered when not set (platform dependent)
   | `transport`               | the transport to use, one of `:nio`, `:epoll`, `:kqueue` or `:io-uring` (defaults to `:nio`)"
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
           epoll?
           transport]
    :or   {max-payload-size        4096
           max-queries-per-resolve 16
           query-timeout           5000
           min-ttl                 0
           max-ttl                 Integer/MAX_VALUE
           trace-enabled?          false
           opt-resources-enabled?  true
           ndots                   -1
           decode-idn?             true
           recursion-desired?      true
           epoll?                  false}}]
  (let [transport (determine-transport transport epoll?)]
    (ensure-transport-available! transport)
    (cond-> (doto (DnsNameResolverBuilder.)
                  (.channelType (transport-channel-type transport))
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
                 (seq search-domains))
            (.searchDomains search-domains)

            (and (some? name-servers)
                 (seq name-servers))
            (.nameServerProvider ^DnsServerAddressStreamProvider
                                 (dns-name-servers-provider name-servers)))))

(defn dns-resolver-group
  "Creates an instance of DnsAddressResolverGroup that might be set as a resolver to
 Bootstrap. The supported options are the same as to `dns-resolver-group-builder`."
  [dns-options]
  (DnsAddressResolverGroup. (dns-resolver-group-builder dns-options)))

(defn ^:no-doc maybe-ssl-handshake-future
  "Returns a deferred which resolves to the channel after a potential
  SSL handshake has completed successfully. If no `SslHandler` is
  present on the associated pipeline, resolves immediately."
  [^Channel ch]
  (if-let [^SslHandler ssl-handler (-> ch .pipeline (.get SslHandler))]
    (do
      (log/debug "Waiting on SSL handshake...")
      (doto (-> ssl-handler
                (.handshakeFuture)
                wrap-future)
            (d/on-realized (fn [_] (log/info "SSL handshake completed"))
                           (fn [_] (log/warn "SSL handshake failed")))))
    (d/success-deferred ch)))

(defn ^:no-doc ignore-ssl-handshake-errors
  "Intended for use as error callback on a `maybe-ssl-handshake-future`
  within a `:channel-active` handler. In this context, SSL handshake
  errors don't need to be handled since the SSL handler will terminate
  the whole pipeline by throwing `javax.net.ssl.SSLHandshakeException`
  anyway."
  [_])

(defn ssl-handler
  "Generates a new SslHandler for the given SslContext.

   The 2-arity version is for the server.
   The 3-arity version is for the client. The `remote-address` must be provided"
  (^ChannelHandler
   [^Channel ch ^SslContext ssl-ctx]
   (.newHandler ssl-ctx
                (.alloc ch)))
  (^ChannelHandler
   [^Channel ch ^SslContext ssl-ctx ^InetSocketAddress remote-address]
   (.newHandler ssl-ctx
                (.alloc ch)
                (.getHostName ^InetSocketAddress remote-address)
                (.getPort ^InetSocketAddress remote-address))))

(defn- add-ssl-handler-to-pipeline-builder
  "Adds an `SslHandler` to the pipeline builder."
  ([pipeline-builder ssl-ctx]
   (add-ssl-handler-to-pipeline-builder pipeline-builder ssl-ctx nil))
  ([pipeline-builder ssl-ctx remote-address]
   (fn [^ChannelPipeline p]
     (.addFirst p
                "ssl-handler"
                (let [ch (.channel p)]
                  (if remote-address
                    (ssl-handler ch ssl-ctx remote-address)
                    (ssl-handler ch ssl-ctx))))
     (pipeline-builder p))))

(defn ^:no-doc create-client-chan
  "Returns a deferred containing a new Channel.

   Deferred will resolve one the channel is connected and ready. If the
   SslHandler is on the pipeline, it will wait for the SSL handshake to
   complete."
  [{:keys [pipeline-builder
           bootstrap-transform
           ^SocketAddress remote-address
           ^SocketAddress local-address
           transport
           name-resolver]
    :as opts}]
  (ensure-transport-available! transport)

  (when (:ssl-context opts)
    (log/warn "The :ssl-context option to `create-client-chan` is deprecated, add it with :pipeline-builder instead")
    (throw (IllegalArgumentException. "Can't use :ssl-context anymore.")))

  (let [^Class chan-class (transport-channel-class transport)
        initializer (pipeline-initializer pipeline-builder)]
    (try
      (let [client-event-loop-group @(transport-client-group transport)
            resolver' (when (some? name-resolver)
                        (cond
                          (= :default name-resolver) nil
                          (= :noop name-resolver) NoopAddressResolverGroup/INSTANCE
                          (instance? AddressResolverGroup name-resolver) name-resolver))
            bootstrap (doto (Bootstrap.)
                            (.option ChannelOption/SO_REUSEADDR true)
                            #_(.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE) ; option deprecated, removed in v5
                            (.group client-event-loop-group)
                            (.channel chan-class)
                            (.handler initializer)
                            (.resolver resolver')
                            bootstrap-transform)

            fut (if local-address
                  (.connect bootstrap remote-address local-address)
                  (.connect bootstrap remote-address))]

        (d/chain' (wrap-future fut)
                  (fn [_]
                    (let [ch (.channel ^ChannelFuture fut)]
                      (maybe-ssl-handshake-future ch))))))))


(defn ^:no-doc ^:deprecated create-client
  "Returns a deferred containing a new Channel.

   If given an SslContext, prepends an SslHandler to the start of the pipeline."
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
    ssl-context
    bootstrap-transform
    ^SocketAddress remote-address
    ^SocketAddress local-address
    epoll?
    name-resolver]
   (let [^SslContext
         ssl-context (when (some? ssl-context)
                       (coerce-ssl-client-context ssl-context))

         pipeline-builder (if ssl-context
                            (add-ssl-handler-to-pipeline-builder pipeline-builder ssl-context remote-address)
                            pipeline-builder)]
     (create-client-chan {:pipeline-builder    pipeline-builder
                          :bootstrap-transform bootstrap-transform
                          :remote-address      remote-address
                          :local-address       local-address
                          :transport           (if epoll? :epoll :nio)
                          :name-resolver       name-resolver}))))


(defn- add-channel-tracker-handler
  "Wraps the pipeline-builder fn with a handler that adds a newly-active
   channel to the common ChannelGroup.

   Useful for things like broadcasting messages to all connected clients."
  [pipeline-builder chan-group]
  (fn [^ChannelPipeline pipeline]
    (.addFirst pipeline "channel-tracker" ^ChannelHandler (channel-tracking-handler chan-group))
    (pipeline-builder pipeline)))

(defn ^:no-doc start-server
  ([pipeline-builder
    ssl-context
    bootstrap-transform
    on-close
    socket-address
    epoll?]
   (start-server {:pipeline-builder    pipeline-builder
                  :ssl-context         ssl-context
                  :bootstrap-transform bootstrap-transform
                  :on-close            on-close
                  :socket-address      socket-address
                  :transport           (if epoll? :epoll :nio)}))
  ([{:keys [pipeline-builder
            ssl-context
            bootstrap-transform
            on-close
            ^SocketAddress socket-address
            transport
            shutdown-timeout]
     :or   {shutdown-timeout default-shutdown-timeout}
     :as   opts}]
   (ensure-transport-available! transport)
   (let [num-cores (.availableProcessors (Runtime/getRuntime))
         num-threads (* 2 num-cores)
         thread-factory (enumerating-thread-factory "aleph-server-pool" false)
         closed? (atom false)
         chan-group (make-channel-group)                    ; a ChannelGroup for all active server Channels

         ^EventLoopGroup group (transport-event-loop-group transport num-threads thread-factory)

         ^Class channel-class (transport-server-channel-class transport)

         ^SslContext
         ssl-context (when (some? ssl-context)
                       (log/warn "The :ssl-context option to `start-server` is discouraged, set up SSL with :pipeline-builder instead.")
                       (coerce-ssl-server-context ssl-context))

         pipeline-builder
         (cond-> (add-channel-tracker-handler pipeline-builder chan-group)
                 (some? ssl-context) (add-ssl-handler-to-pipeline-builder ssl-context))]

     (try
       (let [b (doto (ServerBootstrap.)
                     (.option ChannelOption/SO_BACKLOG (int 1024))
                     (.option ChannelOption/SO_REUSEADDR true)
                     (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                     (.group group)
                     (.channel channel-class)
                     ;;TODO: add a server (.handler) call to the bootstrap, for logging or something
                     (.childHandler (pipeline-initializer pipeline-builder))
                     (.childOption ChannelOption/SO_REUSEADDR true)
                     (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                     bootstrap-transform)

             ^ServerSocketChannel
             ch (-> b (.bind socket-address) .sync .channel)]

         (reify
           Closeable
           (close [_]
             (when (compare-and-set! closed? false true)
               ;; This is the three step closing sequence:
               ;; 1. Stop listening to incoming requests
               (-> ch .close .sync)
               (-> (if (pos? shutdown-timeout)
                     ;; 2. Wait for in-flight requests to stop processing within the supplied timeout
                     ;;    interval.
                     (-> (wrap-future (.newCloseFuture chan-group))
                         (d/timeout! (* shutdown-timeout 1000) ::timeout))
                     (d/success-deferred ::noop))
                   (d/chain'
                     (fn [shutdown-output]
                       (when (= shutdown-output ::timeout)
                         (log/error
                           (format "Timeout while waiting for requests to close (exceeded: %ss)"
                                   shutdown-timeout)))))
                   (d/finally'
                     ;; 3. At this stage, stop the EventLoopGroup, this will cancel any
                     ;;    in flight pending requests.
                     #(.shutdownGracefully group 0 0 TimeUnit/SECONDS)))
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
         (throw e))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; prn support for Netty for debugging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ^:no-doc append-pipeline*
  [^StringBuilder sb ^ChannelPipeline pipeline]
  (.append sb "\tHandlers:\n")
  (run! (fn [[hname handler]]
          (-> sb
              (.append "\t\t")
              (.append hname)
              (.append ": ")
              (.append (StringUtil/simpleClassName handler))
              (.append " (#")
              (.append (.hashCode handler))
              (.append ")")
              (.append "\n")))
        (.toMap pipeline))
  sb)

(defmethod print-method ChannelPipeline
  [^ChannelPipeline pipeline ^java.io.Writer writer]
  (let [sb (StringBuilder.)]
    (-> sb
        (.append (StringUtil/simpleClassName (class pipeline)))
        (.append "\n")
        (append-pipeline* pipeline))
    (.write writer (.toString sb))))

(defn- ^:no-doc append-chan-status*
  [^Channel chan ^StringBuilder sb]
  (if (or (.isRegistered chan) (.isOpen chan) (.isActive chan))
    (do
      (.append sb " (")
      (when (.isRegistered chan)
        (.append sb "REGISTERED,"))
      (when (.isOpen chan)
        (.append sb "OPEN,"))
      (when (.isActive chan)
        (.append sb "ACTIVE,"))
      (.setCharAt sb (dec (.length sb)) \)))
    (.append sb "()")))

(defmethod print-method Http2StreamChannel
  [^Http2StreamChannel chan ^java.io.Writer writer]
  (let [sb (StringBuilder.)]
    (.append sb (StringUtil/simpleClassName (class chan)))
    (.append sb " - stream ID: ")
    (.append sb (-> chan .stream .id))
    (append-chan-status* chan sb)
    (.append sb ":\n")
    (append-pipeline* sb (.pipeline chan))
    (.write writer (.toString sb))))

(defmethod print-method Channel
  [^Channel chan ^java.io.Writer writer]
  (let [sb (StringBuilder.)]
    (.append sb (StringUtil/simpleClassName (class chan)))
    (append-chan-status* chan sb)
    (.append sb ":\n")
    (append-pipeline* sb (.pipeline chan))
    (.write writer (.toString sb))))

(prefer-method print-method Http2StreamChannel Channel)




(comment
  (import '(io.netty.handler.codec.http2 Http2FrameCodecBuilder Http2Settings Http2FrameLogger)
          '(io.netty.handler.logging LogLevel))

  (let [chan (io.netty.channel.embedded.EmbeddedChannel.)
        p (.pipeline chan)]
    (.addLast p
              "test-handler"
              (-> (io.netty.handler.codec.http2.Http2FrameCodecBuilder/forClient)
                  (.initialSettings (Http2Settings/defaultSettings))
                  (.build)))
    (prn p))

  )
