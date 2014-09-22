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

                   (do
                     (.flush ch)
                     body))]

    (let [src (if (or (sequential? body') (s/stream? body'))
                (->> body'
                  s/->source
                  (s/map (fn [x]
                           (try
                             (netty/to-byte-buf x)
                             (catch Throwable e
                               (log/error e "error converting " (.getName (class x)) " to ByteBuf")
                               (.close ch))))))
                (netty/to-byte-buf-stream body' 8192))

          sink (netty/sink ch false #(DefaultHttpContent. %))]

      (s/connect src sink)

      (let [d (d/deferred)]
        (s/on-closed sink #(d/success! d true))
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
                (let [class-name (.getName (class body))]
                  (try
                    (send-streaming-body ch rsp body)
                    (catch Throwable e
                      (log/error e "error sending body of type " class-name)))))]

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
   executor
   req
   previous-response
   body
   keep-alive?]
  (let [req' (http/netty-request->ring-request req ch body)
        rsp (try
              (d/future-with executor
                (handler req'))
              (catch RejectedExecutionException e
                {:status 503
                 :headers {"content-type" "text/plain"}
                 :body "503 Service Unavailable"}))]
    (-> previous-response
      (d/chain'
        netty/wrap-channel-future
        (fn [_]
          (netty/release req)
          (netty/release body)
          (-> rsp
            (d/catch' error-response)
            (d/chain'
              (fn [rsp]
                (send-response ch keep-alive?
                  (if (map? rsp)
                    rsp
                    (invalid-value-response rsp)))))))))))

(defn ring-handler
  [f executor buffer-capacity]
  (let [request (atom nil)
        buffer (atom [])
        buffer-size (AtomicInteger. 0)
        stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^Channel ch req body]
          (reset! previous-response
            (handle-request
              ch
              f
              executor
              req
              @previous-response
              body
              (HttpHeaders/isKeepAlive req))))]
    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s))
         (doseq [b @buffer]
           (netty/release b)))

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
               (let [s (s/buffered-stream #(alength ^bytes %) 65536)]
                 (reset! stream s)
                 (handle-request (.channel ctx) req s))
               (reset! request req)))

           (instance? HttpContent msg)
           (let [content (.content ^HttpContent msg)]
             (if (instance? LastHttpContent msg)
               (do

                 (if-let [s @stream]

                   (do
                     (s/put! s (netty/buf->array content))
                     (netty/release content)
                     (s/close! s))

                   (let [bufs (conj @buffer content)
                         bytes (netty/bufs->array bufs)]
                     (doseq [b bufs]
                       (netty/release b))
                     (handle-request (.channel ctx) @request bytes)))

                 (.set buffer-size 0)
                 (reset! stream nil)
                 (reset! buffer [])
                 (reset! request nil))

               (if-let [s @stream]

                 ;; already have a stream going
                 (do
                   (netty/put! (.channel ctx) s (netty/buf->array content))
                   (netty/release content))

                 (do

                   (swap! buffer conj content)

                   (let [size (.addAndGet buffer-size (.readableBytes ^ByteBuf content))]

                     ;; buffer size exceeded, flush it as a stream
                     (when (< buffer-capacity size)
                       (let [bufs @buffer
                             s (doto (s/buffered-stream #(alength ^bytes %) 16384)
                                 (s/put! (netty/bufs->array bufs)))]

                         (doseq [b bufs]
                           (netty/release b))

                         (reset! buffer [])
                         (reset! stream s)

                         (handle-request (.channel ctx) @request s)))))))))))))

(defn raw-ring-handler
  [f executor buffer-capacity]
  (let [stream (atom nil)
        previous-response (atom nil)

        handle-request
        (fn [^Channel ch req body]
          (reset! previous-response
            (handle-request
              ch
              f
              executor
              req
              @previous-response
              body
              (HttpHeaders/isKeepAlive req))))]
    (netty/channel-handler

      :exception-handler
      ([_ ctx ex]
         (log/warn ex "error in HTTP server"))

      :channel-inactive
      ([_ ctx]
         (when-let [s @stream]
           (s/close! s)))

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

             (let [s (s/buffered-stream #(.readableBytes ^ByteBuf %) buffer-capacity)]
               (reset! stream s)
               (handle-request (.channel ctx) req s)))

           (instance? HttpContent msg)
           (let [content (.content ^HttpContent msg)]
             (s/put! @stream content)
             (when (instance? LastHttpContent msg)
               (s/close! @stream))))))))

(defn pipeline-builder
  [handler
   {:keys
    [executor
     request-buffer-size
     max-initial-line-length
     max-header-size
     max-chunk-size
     raw-stream?]
    :or
    {request-buffer-size 16384
     max-initial-line-length 4098
     max-header-size 8196
     max-chunk-size 8196}}]
  (assert executor "must define executor for HTTP server")
  (fn [^ChannelPipeline pipeline]
    (let [handler (if raw-stream?
                    (raw-ring-handler handler executor request-buffer-size)
                    (ring-handler handler executor request-buffer-size))]
      (doto pipeline
        (.addLast "http-server"
          (HttpServerCodec.
            max-initial-line-length
            max-header-size
            max-chunk-size
            false))
        (.addLast "handler" ^ChannelHandler handler)))))

;;;

(defn wrap-stream->input-stream
  [f
   {:keys [request-buffer-size]
    :or {request-buffer-size 16384}}]
  (fn [req]
    (let [body (:body req)]
      (f
        (assoc req :body
          (when body
            (bs/convert body InputStream
              {:buffer-size request-buffer-size
               :source-type (bs/stream-of netty/array-class)})))))))

(defn start-server
  [handler
   {:keys [port executor raw-stream? bootstrap-transform]
    :or {bootstrap-transform identity}
    :as options}]
  (netty/start-server
    (pipeline-builder
      (if raw-stream?
        handler
        (wrap-stream->input-stream handler options))
      options)
    bootstrap-transform
    port))
