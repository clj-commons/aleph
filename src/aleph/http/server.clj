(ns aleph.http.server
  (:require
    [aleph.http.core :as http]
    [aleph.netty :as netty]
    [byte-streams :as bs]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    [io.netty.buffer
     ByteBuf Unpooled]
    [io.netty.channel
     Channel ChannelFuture
     ChannelFutureListener ChannelHandler
     ChannelPipeline DefaultFileRegion]
    [io.netty.handler.codec.http
     DefaultFullHttpResponse
     DefaultHttpContent
     DefaultLastHttpContent
     HttpContent HttpHeaders
     HttpRequest HttpResponse
     HttpResponseStatus
     HttpServerCodec HttpVersion
     LastHttpContent]
    [java.io
     Closeable File InputStream RandomAccessFile]
    [java.nio
     ByteBuffer]
    [java.util.concurrent
     RejectedExecutionException]
    [java.util.concurrent.atomic
     AtomicInteger]))

;;;

(defn send-response
  [^Channel ch keep-alive? rsp]
  (let [body (get rsp :body)
        ^HttpResponse rsp (http/ring-response->netty-response rsp)]

    (doto (.headers rsp)
      (.set "Server" "Aleph/0.4.0")
      (.set "Connection" (if keep-alive? "Keep-Alive" "Close")))

    (http/send-message ch keep-alive? rsp body)))

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
        netty/wrap-future
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
               (let [s (s/buffered-stream #(alength ^bytes %) buffer-capacity)]
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
                   #_(s/put! s (netty/buf->array content))
                   (netty/release content))

                 (do

                   (swap! buffer conj content)

                   (let [size (.addAndGet buffer-size (.readableBytes ^ByteBuf content))]

                     ;; buffer size exceeded, flush it as a stream
                     (when (< buffer-capacity size)
                       (let [bufs @buffer
                             s (doto (s/buffered-stream #(alength ^bytes %) buffer-capacity)
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
             (netty/put! (.channel ctx) @stream content)
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
  (fn [{:keys [body] :as req}]
    (f
      (assoc req :body
        (when body
          (netty/to-input-stream body request-buffer-size))))))

(defn start-server
  [handler
   {:keys [port executor raw-stream? bootstrap-transform ssl-context]
    :or {bootstrap-transform identity}
    :as options}]
  (netty/start-server
    (pipeline-builder
      (if raw-stream?
        handler
        (wrap-stream->input-stream handler options))
      options)
    ssl-context
    bootstrap-transform
    port))
