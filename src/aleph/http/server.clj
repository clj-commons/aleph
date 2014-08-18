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

;;;

(def empty-last-content LastHttpContent/EMPTY_LAST_CONTENT)

(let [ary-class (class (byte-array 0))]
  (defn coerce-element [x]
    (if (or
          (instance? String x)
          (instance? ary-class x)
          (instance? ByteBuffer x)
          (instance? ByteBuf x))
      (netty/to-byte-buf x)
      (netty/to-byte-buf (str x)))))

(defn send-streaming-body [^Channel ch ^HttpResponse rsp body]

  (HttpHeaders/setTransferEncodingChunked rsp)
  (netty/write ch rsp)

  (if-let [body' (if (sequential? body)

                   ;; just unroll the seq, if we can
                   (loop [s (map coerce-element body)]
                     (if (empty? s)
                       (do
                         (.flush ch)
                         nil)
                       (if (or (not (instance? clojure.lang.IPending s))
                             (realized? s))
                         (let [x (first s)]
                           (netty/write ch (netty/to-byte-buf x))
                           (recur (rest s)))
                         body)))

                   body)]

    (let [src (netty/to-byte-buf-stream body' 8192)]

      (.flush ch)

      (s/connect
        src
        (netty/sink ch #(DefaultHttpContent. %))
        {:downstream? false})

      (let [d (d/deferred)]
        (s/on-drained src #(d/success! d true))
        (d/chain' d
          (fn [_]
            (when (instance? Closeable body)
              (.close ^Closeable body))
            (netty/write-and-flush ch empty-last-content)))))

    (netty/write-and-flush ch empty-last-content)))

(defn send-file-body [^Channel ch ^HttpResponse rsp ^File file]
  (let [raf (RandomAccessFile. file "r")
        len (.length raf)
        fc (.getChannel raf)
        fr (DefaultFileRegion. fc 0 len)]
    (HttpHeaders/setContentLength rsp len)
    (netty/write ch rsp)
    (netty/write ch fr)
    (netty/write-and-flush ch empty-last-content)))

(defn send-contiguous-body [^Channel ch ^HttpResponse rsp body]
  (let [body (if body
               (netty/to-byte-buf body)
               Unpooled/EMPTY_BUFFER)]
    (HttpHeaders/setContentLength rsp (.readableBytes body))
    (netty/write ch rsp)
    (netty/write-and-flush ch (DefaultLastHttpContent. body))))

(let [ary-class (class (byte-array 0))]
  (defn send-response
    [^Channel ch keep-alive? rsp]
    (let [body (get rsp :body)
          ^HttpResponse rsp (http/ring-response->netty-response rsp)
          ^HttpHeaders headers (.headers rsp)]

      (.set headers "Server" "Aleph/0.4.0")
      (.set headers "Connection" (if keep-alive? "Keep-Alive" "Close"))

      (let [f (cond

                (or
                  (instance? String body)
                  (instance? ary-class body)
                  (instance? ByteBuffer body)
                  (instance? ByteBuf body))
                (send-contiguous-body ch rsp body)

                (instance? File body)
                (send-file-body ch rsp body)

                :else
                (send-streaming-body ch rsp body))]

        (when-not keep-alive?
          (-> f
            (d/chain'
              (fn [^ChannelFuture f]
                (.addListener f ChannelFutureListener/CLOSE)))
            (d/catch Throwable #(log/error % "err"))))

        f))))

;;;

(defn error-response [^Throwable e]
  (log/error e "error in HTTP handler")
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

(defn handle-request
  [ch
   handler
   req
   previous-response
   body
   keep-alive?]
  (let [req (http/netty-request->ring-request req ch body)
        rsp (try
              (handler req)
              (catch Throwable e
                (error-response e)))]
    (-> previous-response
      (d/chain'
        netty/wrap-channel-future
        (fn [_]
          (if (map? rsp)
            (send-response ch keep-alive? rsp)
            (let [d (d/->deferred rsp ::none)]
              (if (identical? ::none d)

                (send-response ch keep-alive? (invalid-value-response rsp))

                (-> d
                  (d/catch error-response)
                  (d/chain'
                    (fn [rsp]
                      (send-response ch keep-alive?
                        (if (map? rsp)
                          rsp
                          (invalid-value-response rsp))))))))))))))

(defn ring-handler
  [f]
  (let [request (atom nil)
        chunks (atom nil)
        previous-response (atom nil)]
    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-read
      ([_ ctx msg]
         (cond

           (instance? HttpRequest msg)
           (let [req msg]

             (when (HttpHeaders/is100ContinueExpected req)
               (.writeAndFlush ctx
                 (DefaultFullHttpResponse.
                   HttpVersion/HTTP_1_1
                   HttpResponseStatus/CONTINUE)))

             (if (HttpHeaders/isTransferEncodingChunked req)
               (let [s (s/stream)]
                 (reset! chunks s)
                 (reset! previous-response
                   (handle-request (.channel ctx) f req @previous-response s (HttpHeaders/isKeepAlive req))))
               (reset! request req)))

           (instance? HttpContent msg)
           (let [body (.content ^HttpContent msg)
                 keep-alive? (HttpHeaders/isKeepAlive @request)]
             (if (instance? LastHttpContent msg)
               (do
                 (if-let [chunks @chunks]
                   (s/close! chunks)
                   (reset! previous-response
                     (handle-request (.channel ctx) f @request @previous-response  body keep-alive?)))
                 (when keep-alive?
                   (reset! chunks nil)
                   (reset! request nil)))
               (netty/put! (.channel ctx) @chunks keep-alive?)))

           )))))

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

;;;

(defn wrap-stream->input-stream
  [f]
  (fn [req]
    (let [body (:body req)]
      (f
        (assoc req :body
          (when body
            (bs/to-input-stream body)))))))

(defn start-server
  [handler
   {:keys [port]}]
  (netty/start-server
    (-> handler
      wrap-stream->input-stream
      pipeline-builder)
    identity
    port))
