(ns aleph.netty
  (:refer-clojure :exclude [flush])
  (:require
    [byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [manifold.stream.core :as manifold]
    [primitive-math :as p])
  (:import
    [io.netty.bootstrap Bootstrap ServerBootstrap]
    [io.netty.buffer ByteBuf PooledByteBufAllocator Unpooled]
    [io.netty.channel
     Channel ChannelFuture ChannelOption
     ChannelPipeline EventLoopGroup
     ChannelHandler
     ChannelInboundHandler
     ChannelOutboundHandler
     ChannelHandlerContext]
    [io.netty.channel.epoll Epoll EpollEventLoopGroup
     EpollServerSocketChannel
     EpollSocketChannel]
    [io.netty.channel.nio NioEventLoopGroup]
    [io.netty.channel.socket ServerSocketChannel]
    [io.netty.channel.socket.nio NioServerSocketChannel
     NioSocketChannel]
    [io.netty.handler.ssl SslContext]
    [io.netty.handler.ssl.util
     SelfSignedCertificate InsecureTrustManagerFactory]
    [io.netty.util ResourceLeakDetector
     ResourceLeakDetector$Level]
    [java.net SocketAddress InetSocketAddress]
    [io.netty.util.concurrent
     GenericFutureListener Future DefaultThreadFactory]
    [java.io InputStream]
    [java.nio ByteBuffer]
    [io.netty.util.internal SystemPropertyUtil]
    [java.util.concurrent CancellationException]
    [io.netty.util.internal.logging
     InternalLoggerFactory
     Log4JLoggerFactory
     Slf4JLoggerFactory
     JdkLoggerFactory]))

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
    (case
      :log4j (Log4JLoggerFactory.)
      :slf4j (Slf4JLoggerFactory.)
      :jdk   (JdkLoggerFactory.))))

;;;

(def ^:const array-class (class (clojure.core/byte-array 0)))

(defn buf->array [^ByteBuf buf]
  (let [dst (ByteBuffer/allocate (.readableBytes buf))]
    (doseq [^ByteBuffer buf (.nioBuffers buf)]
      (.put dst buf))
    (.array dst)))

(defn release-buf->array [^ByteBuf buf]
  (let [ary (buf->array buf)]
    (release buf)
    ary))

(defn bufs->array [bufs]
  (let [bufs' (mapcat #(.nioBuffers ^ByteBuf %) bufs)
        dst (ByteBuffer/allocate (loop [cnt 0, s bufs']
                                   (if (empty? s)
                                     cnt
                                     (recur (p/+ cnt (.remaining ^ByteBuffer (first s))) (rest s)))))]
    (doseq [^ByteBuffer buf bufs']
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
      (do
        (release x)
        (.writeBytes buf ^ByteBuf x))

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
      (if (nil? x)
        Unpooled/EMPTY_BUFFER
        (doto (allocate ch)
          (append-to-buf! x))))))

(defn to-byte-buf-stream [x chunk-size]
  (bs/convert x (bs/stream-of ByteBuf) {:chunk-size chunk-size}))


(defn wrap-future
  [^Future f]
  (when f
    (if (.isSuccess f)
      (d/success-deferred true)
      (let [d (d/deferred)]
        (.addListener f
          (reify GenericFutureListener
            (operationComplete [_ _]
              (cond
                (.isSuccess f)
                (d/success! d true)

                (.isCancelled f)
                (d/error! d (CancellationException. "future is cancelled."))

                (some? (.cause f))
                (if (instance? java.nio.channels.ClosedChannelException (.cause f))
                  (d/success! d false)
                  (d/error! d (.cause f)))

                :else
                (d/error! d (IllegalStateException. "future in unknown state"))))))
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
    (if (.isWritable ^Channel x)
      (do
        (.writeAndFlush ^Channel x msg (.voidPromise ^Channel x))
        nil)
      (.writeAndFlush ^Channel x msg))
    (if (-> ^ChannelHandlerContext x .channel .isWritable)
      (do
        (.writeAndFlush ^ChannelHandlerContext x msg (.voidPromise ^ChannelHandlerContext x))
        nil)
      (.writeAndFlush ^ChannelHandlerContext x msg))))

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
    (if (d/realized? d)
      (if @d
        true
        (do
          (.close ch)
          false))
      (do
        (-> ch .config (.setAutoRead false))
        #_(prn 'backpressure)
        (d/chain' d
          (fn [result]
            (when-not result
              (.close ch))
            #_(prn 'backpressure-off)
            (-> ch .config (.setAutoRead true))))
        d))))

;;;

(manifold/def-sink ChannelSink [coerce-fn downstream? ch]
  (close [this]
    (when downstream?
      (close ch))
    (.markClosed this)
    true)
  (description [_]
    {:type "netty"
     :sink? true
     :closed? (not (.isOpen (channel ch)))})
  (isSynchronous [_]
    false)
  (put [this msg blocking?]
    (let [msg (try
                (coerce-fn msg)
                (catch Exception e
                  (log/error e
                    (str "cannot coerce "
                      (.getName (class msg))
                      " into binary representation"))
                  (close ch)))
          ^ChannelFuture f (write-and-flush ch msg)
          d (or (wrap-future f) (d/success-deferred true))]
      (if blocking?
        @d
        d)))
  (put [this msg blocking? timeout timeout-value]
    (.put this msg blocking?)))

(defn sink
  ([ch]
    (sink ch true identity))
  ([ch downstream? coerce-fn]
    (let [sink (->ChannelSink coerce-fn downstream? ch)]
      (d/chain' (.closeFuture (channel ch))
        wrap-future
        (fn [_] (s/close! sink)))
      sink)))

;;;

(defmacro channel-handler
  [& {:as handlers}]
  `(reify
     ChannelHandler
     ChannelInboundHandler
     ChannelOutboundHandler

     (handlerAdded
       ~@(or (:handler-added handlers) `([_ _])))
     (handlerRemoved
       ~@(or (:handler-removed handlers) `([_ _])))
     (exceptionCaught
       ~@(or (:exception-caught handlers)
           `([_ ctx# cause#]
              (.fireExceptionCaught ctx# cause#))))
     (channelRegistered
       ~@(or (:channel-registered handlers)
           `([_ ctx#]
              (.fireChannelRegistered ctx#))))
     (channelUnregistered
       ~@(or (:channel-unregistered handlers)
           `([_ ctx#]
              (.fireChannelUnregistered ctx#))))
     (channelActive
       ~@(or (:channel-active handlers)
           `([_ ctx#]
              (.fireChannelActive ctx#))))
     (channelInactive
       ~@(or (:channel-inactive handlers)
           `([_ ctx#]
              (.fireChannelInactive ctx#))))
     (channelRead
       ~@(or (:channel-read handlers)
           `([_ ctx# msg#]
              (.fireChannelRead ctx# msg#))))
     (channelReadComplete
       ~@(or (:channel-read-complete handlers)
           `([_ ctx#]
              (.fireChannelReadComplete ctx#))))
     (userEventTriggered
       ~@(or (:user-event-triggered handlers)
           `([_ ctx# evt#]
              (.fireUserEventTriggered ctx# evt#))))
     (channelWritabilityChanged
       ~@(or (:channel-writability-changed handlers)
           `([_ ctx#]
              (.fireChannelWritabilityChanged ctx#))))
     (bind
       ~@(or (:bind handlers)
           `([_ ctx# local-address# promise#]
              (.bind ctx# local-address# promise#))))
     (connect
       ~@(or (:connect handlers)
           `([_ ctx# remote-address# local-address# promise#]
              (.connect ctx# remote-address# local-address# promise#))))
     (disconnect
       ~@(or (:disconnect handlers)
           `([_ ctx# promise#]
              (.disconnect ctx# promise#))))
     (close
       ~@(or (:close handlers)
           `([_ ctx# promise#]
              (.close ctx# promise#))))
     (read
       ~@(or (:read handlers)
           `([_ ctx#]
              (.read ctx#))))
     (write
       ~@(or (:write handlers)
           `([_ ctx# msg# promise#]
              (.write ctx# msg# promise#))))
     (flush
       ~@(or (:flush handlers)
           `([_ ctx#]
              (.flush ctx#))))))

(defn pipeline-initializer [pipeline-builder]
  (channel-handler

    :channel-registered
    ([this ctx]
      (let [pipeline (.pipeline ctx)]
        (try
          (.remove pipeline this)
          (pipeline-builder pipeline)
          (.fireChannelRegistered ctx)
          (catch Throwable e
            (log/warn e "Failed to initialize channel")
            (.close ctx)))))))

;;;

(defn self-signed-ssl-context
  "A self-signed SSL context for servers."
  []
  (let [cert (SelfSignedCertificate.)]
    (SslContext/newServerContext (.certificate cert) (.privateKey cert))))

(defn insecure-ssl-client-context []
  (SslContext/newClientContext InsecureTrustManagerFactory/INSTANCE))

(defn ssl-client-context []
  (SslContext/newClientContext))

;;;

(defprotocol AlephServer
  (port [_] "Returns the port the server is listening on."))

(defn epoll? []
  (Epoll/isAvailable))

(defn get-default-event-loop-threads
  "Determines the default number of threads to use for a Netty EventLoopGroup.
   This mimics the default used by Netty as of version 4.1."
  []
  (let [cpu-count (->> (Runtime/getRuntime) (.availableProcessors))]
    (max 1 (SystemPropertyUtil/getInt "io.netty.eventLoopThreads" (* cpu-count 2)))))

(def ^String client-event-thread-pool-name "aleph-netty-client-event-pool")

(def client-group
  (let [thread-count (get-default-event-loop-threads)
        thread-factory (DefaultThreadFactory. client-event-thread-pool-name true)]
    (if (epoll?)
      (EpollEventLoopGroup. (long thread-count) thread-factory)
      (NioEventLoopGroup. (long thread-count) thread-factory))))

(defn create-client
  [pipeline-builder
   ^SslContext ssl-context
   bootstrap-transform
   ^SocketAddress remote-address
   ^SocketAddress local-address]
  (let [^Class
        channel (if (epoll?)
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
      (let [b (doto (Bootstrap.)
                (.option ChannelOption/SO_REUSEADDR true)
                (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                (.group client-group)
                (.channel channel)
                (.handler (pipeline-initializer pipeline-builder))
                bootstrap-transform)

            f (if local-address
                (.connect b remote-address local-address)
                (.connect b remote-address))]

        (d/chain' (wrap-future f)
          (fn [_]
            (let [ch (.channel ^ChannelFuture f)]
              ch)))))))

(defn start-server
  [pipeline-builder
   ^SslContext ssl-context
   bootstrap-transform
   on-close
   ^SocketAddress socket-address]
  (let [^EventLoopGroup
        group (if (epoll?)
                (EpollEventLoopGroup.)
                (NioEventLoopGroup.))

        ^Class
        channel (if (epoll?)
                  EpollServerSocketChannel
                  NioServerSocketChannel)

        pipeline-builder (if ssl-context
                           (fn [^ChannelPipeline p]
                             (.addLast p "ssl-handler"
                               (.newHandler ssl-context
                                 (-> p .channel .alloc)))
                             (pipeline-builder p))
                           pipeline-builder)]

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
          (when on-close (on-close))
          (-> ch .close .sync)
          (-> group .shutdownGracefully wrap-future))
        AlephServer
        (port [_]
          (-> ch .localAddress .getPort))))))
