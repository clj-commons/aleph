(ns aleph.http.server
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
     RandomAccessFile]
    [io.netty.buffer
     ByteBuf
     Unpooled]
    [io.netty.channel
     ChannelPipeline
     ChannelHandlerContext
     ChannelFutureListener
     ChannelFuture
     ChannelHandler
     DefaultFileRegion]
    [io.netty.handler.codec.http
     HttpMessage
     HttpServerCodec
     HttpHeaders
     HttpRequest
     HttpResponse
     DefaultLastHttpContent
     HttpVersion
     HttpResponseStatus]))

(defn send-file-body [^ChannelHandlerContext ctx ^HttpResponse rsp ^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc 0 len)]
    (-> rsp .headers (.set "Content-Length" (str len)))
    (.write ctx rsp)
    (let [f (.writeAndFlush ctx (DefaultLastHttpContent. fr))]
      (-> f
        (d/chain (fn [_] (.close fc)))
        (d/catch Throwable (fn [_])))
      f)))

(defn send-contiguous-body [^ChannelHandlerContext ctx ^HttpResponse rsp body]
  (let [body (if body
               (netty/to-byte-buf body)
               Unpooled/EMPTY_BUFFER)]
    (-> rsp .headers (.set "Content-Length" (-> body .readableBytes str)))
    (.write ctx rsp)
    (.writeAndFlush ctx (DefaultLastHttpContent. body))))

(defn send-response
  [^ChannelHandlerContext ctx keep-alive? rsp]
  (let [body (get rsp :body)
        ^HttpResponse rsp (http/ring-response->netty-response rsp)
        ^HttpHeaders headers (.headers rsp)]

    (.set headers "Server" "Aleph/0.4.0")
    (.set headers "Connection" (if keep-alive? "Keep-Alive" "Close"))

    (let [f (send-contiguous-body ctx rsp body)]
      (when-not keep-alive?
        (.addListener ^ChannelFuture f ChannelFutureListener/CLOSE)))))

(defn error-response [^Throwable e]
  {:status 500
   :headers {"content-type" "text/plain"}
   :body (let [w (java.io.StringWriter.)]
           (binding [*err* w]
             (.printStackTrace e)
             (str w)))})

(defn invalid-value-response [x]
  (error-response
    (IllegalArgumentException.
      (str "cannot treat " (pr-str x) "as HTTP response"))))

(defn ring-handler
  [f]
  (netty/channel-handler

    :exception-handler
    ([_ ctx ex]
       (log/warn ex "error in HTTP server"))

    :channel-read
    ([_ ctx msg]
       (when (instance? HttpRequest msg)
         (let [req msg
               keep-alive? (HttpHeaders/isKeepAlive req)]

           (when (HttpHeaders/is100ContinueExpected req)
             (.write ctx
               (DefaultFullHttpResponse.
                 HttpVersion/HTTP_1_1
                 HttpResponseStatus/CONTINUE)))

           (let [rsp (try
                       (f (http/netty-request->ring-request req (.channel ctx) nil))
                       (catch Throwable e
                         (error-response e)))]
             (if (map? rsp)
               (send-response ctx keep-alive? rsp)
               (let [d (d/->deferred rsp ::none)]
                 (if (identical? ::none d)
                   (send-response ctx keep-alive? (invalid-value-response rsp))
                   (-> d
                     (d/catch error-response)
                     (d/chain
                       (fn [rsp]
                         (send-response ctx keep-alive?
                           (if (map? rsp)
                             rsp
                             (invalid-value-response rsp)))))))))))))))

(defn pipeline-builder
  ([handler]
     (pipeline-builder
       handler
       4098
       8196
       8196))
  ([handler
    max-initial-line-length
    max-header-size
    max-chunk-size]
     (fn [^ChannelPipeline pipeline]
       (doto pipeline
         (.addLast "http-server"
           (HttpServerCodec.
             max-initial-line-length
             max-header-size
             max-chunk-size
             false))
         (.addLast "handler"
           ^ChannelHandler
           (ring-handler handler))))))

(defn -main [& args]
  (netty/start-server (pipeline-builder (fn [req] {:status 200, :body "hello"})) identity 8080))
