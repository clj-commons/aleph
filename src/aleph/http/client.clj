(ns aleph.http.client
  (:require
    [clojure.tools.logging :as log]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http.core :as http]
    [aleph.netty :as netty])
  (:import
    [java.io
     File
     InputStream
     RandomAccessFile
     Closeable]
    [java.nio
     ByteBuffer]
    [io.netty.buffer
     ByteBuf
     Unpooled]
    [io.netty.channel
     Channel
     ChannelPipeline
     ChannelHandlerContext
     ChannelFutureListener
     ChannelFuture
     ChannelHandler
     DefaultFileRegion]
    [java.util.concurrent
     RejectedExecutionException]
    [java.util.concurrent.atomic
     AtomicInteger]
    [io.netty.handler.codec.http
     HttpMessage
     HttpServerCodec
     HttpHeaders
     HttpRequest
     HttpResponse
     HttpContent
     LastHttpContent
     DefaultLastHttpContent
     DefaultHttpContent
     DefaultFullHttpResponse
     HttpVersion
     HttpResponseStatus]))
