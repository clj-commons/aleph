(ns aleph.netty
  (:require
    [primitive-math :as p]
    [clojure.tools.logging :as log]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    [java.nio
     ByteBuffer]
    [io.netty.buffer
     Unpooled
     PooledByteBufAllocator
     ByteBuf]
    [io.netty.channel
     Channel
     ChannelFuture
     ChannelPromise
     ChannelPipeline
     ChannelHandler
     ChannelOption
     ChannelInboundHandler
     ChannelOutboundHandler
     ChannelFutureListener
     EventLoopGroup]
    [io.netty.util
     ReferenceCountUtil
     ResourceLeakDetector
     ResourceLeakDetector$Level]
    [io.netty.channel.socket
     ServerSocketChannel]
    [io.netty.util.concurrent
     GenericFutureListener]
    [io.netty.channel.epoll
     Epoll
     EpollEventLoopGroup
     EpollSocketChannel
     EpollServerSocketChannel]
    [io.netty.channel.nio
     NioEventLoopGroup]
    [io.netty.channel.socket.nio
     NioSocketChannel
     NioServerSocketChannel]
    [io.netty.bootstrap
     Bootstrap
     ServerBootstrap]))

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

;;;

(def ^:const array-class (class (clojure.core/byte-array 0)))

(defn buf->array [^ByteBuf buf]
  (let [dst (ByteBuffer/allocate (.readableBytes buf))]
    (doseq [^ByteBuffer buf (.nioBuffers buf)]
      (.put dst buf))
    (.array dst)))

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

(defn ^ByteBuf to-byte-buf [x]
  (bs/convert x ByteBuf))

(defn to-byte-buf-stream [x chunk-size]
  (bs/convert x (bs/stream-of ByteBuf) {:chunk-size chunk-size}))

(defn wrap-channel-future [^ChannelFuture f]
  (when f
    (let [d (d/deferred)]
      (.addListener f
        (reify GenericFutureListener
          (operationComplete [_ _]
            (d/success! d (.isSuccess f)))))
      d)))

;;;

(defn write [^Channel ch msg]
  (.write ch msg (.voidPromise ch)))

(defn write-and-flush [^Channel ch msg]
  (.writeAndFlush ch msg))

(defn put! [^Channel ch s msg]
  (let [d (s/put! s msg)]
    (if (d/realized? d)
      true
      (do
        (-> ch .config (.setAutoRead false))
        #_(prn 'backpressure)
        (d/chain d
          (fn [_]
            (-> ch .config (.setAutoRead true))
            #_(prn 'backpressure-off)))
        d))))

;;;

(s/def-sink ChannelSink [coerce-fn downstream? ^Channel ch]
  (close [this]
    (when downstream?
      (.close ch))
    (.markClosed this)
    true)
  (description [_]
    {:type "netty"
     :sink? true
     :closed? (not (.isOpen ch))
     })
  (isSynchronous [_]
    false)
  (put [this msg blocking?]
    (let [^ChannelFuture f (.writeAndFlush ch (coerce-fn msg))
          d (wrap-channel-future f)]
      (if blocking?
        @d
        d)))
  (put [this msg blocking? timeout timeout-value]
    (.put this msg blocking?)))

(defn sink
  ([ch]
     (sink ch true identity))
  ([^Channel ch downstream? coerce-fn]
     (let [sink (->ChannelSink coerce-fn downstream? ch)]
       (d/chain' (.closeFuture ch)
         wrap-channel-future
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

(defprotocol AlephServer
  (port [_] "Returns the port the server is listening on."))

(defn epoll? []
  (Epoll/isAvailable))

(defn create-client
  [pipeline-builder bootstrap-transform host port]
  (let [^EventLoopGroup
        group (if (epoll?)
                (EpollEventLoopGroup.)
                (NioEventLoopGroup.))

        ^Class
        channel (if (epoll?)
                  EpollSocketChannel
                  NioSocketChannel)]
    (try
      (let [b (doto (Bootstrap.)
                (.option ChannelOption/SO_BACKLOG (int 1024))
                (.option ChannelOption/SO_REUSEADDR true)
                (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
                (.group group)
                (.channel channel)
                (.handler (pipeline-initializer pipeline-builder))
                bootstrap-transform)]

        (d/->deferred (.connect b ^String host (int port)))))))

(defn start-server
  [pipeline-builder bootstrap-transform port]
  (let [^EventLoopGroup
        group (if (epoll?)
                (EpollEventLoopGroup.)
                (NioEventLoopGroup.))

        ^Class
        channel (if (epoll?)
                  EpollServerSocketChannel
                  NioServerSocketChannel)]

    (let [b (doto (ServerBootstrap.)
              (.option ChannelOption/SO_BACKLOG (int 1024))
              (.option ChannelOption/SO_REUSEADDR true)
              (.option ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
              (.group group)
              (.channel channel)
              (.childHandler (pipeline-initializer pipeline-builder))
              (.childOption ChannelOption/ALLOCATOR PooledByteBufAllocator/DEFAULT)
              (.childOption ChannelOption/SO_REUSEADDR true)
              (.childOption ChannelOption/MAX_MESSAGES_PER_READ Integer/MAX_VALUE)
              bootstrap-transform)

          ^ServerSocketChannel
          ch (-> b (.bind (int port)) .sync .channel)]
      (reify
        java.io.Closeable
        (close [_]
          (-> ch .close .sync)
          (-> group .shutdownGracefully .sync))
        AlephServer
        (port [_]
          (-> ch .localAddress .getPort))))))
