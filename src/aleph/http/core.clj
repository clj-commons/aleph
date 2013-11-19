;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.core
  (:use
    [potemkin]
    [aleph.netty.core :only (rfc-1123-date-string)]
    [lamina core api executor])
  (:require
    [aleph.http.options :as options]
    [aleph.netty.client :as client]
    [aleph.formats :as formats]
    [aleph.netty :as netty]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    [java.io
     RandomAccessFile
     InputStream
     File]
    [org.jboss.netty.handler.codec.http
     DefaultHttpChunk
     DefaultHttpResponse
     DefaultHttpRequest
     HttpVersion
     HttpResponseStatus
     HttpResponse
     HttpRequest
     HttpMessage
     HttpMethod
     HttpRequest
     HttpChunk
     HttpHeaders]
    [org.jboss.netty.channel
     Channel]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [java.nio
     ByteBuffer]
    [java.nio.channels
     FileChannel
     FileChannel$MapMode]
    [java.net
     URLConnection
     InetAddress
     InetSocketAddress]))

(def request-methods [:get :post :put :delete :trace :connect :head :options :patch])

(def netty-method->keyword
  (zipmap
    (map #(HttpMethod/valueOf (str/upper-case (name %))) request-methods)
    request-methods))

(def keyword->netty-method
  (zipmap
    (vals netty-method->keyword)
    (keys netty-method->keyword)))

(defn request-method [^HttpRequest request]
  (netty-method->keyword (.getMethod request)))

(defn response-code [^HttpResponse response]
  (-> response .getStatus .getCode))

(defn http-headers [^HttpMessage msg]
  (let [k (keys (.getHeaders msg))]
    (zipmap
      (map str/lower-case k)
      (map #(.getHeader msg %) k))))

(defn http-body [^HttpMessage msg]
  (.getContent msg))

(defn http-content-type [^HttpMessage msg]
  (.getHeader msg "Content-Type"))

(defn http-character-encoding [^HttpMessage msg]
  (when-let [content-type (.getHeader msg "Content-Type")]
    (->> (str/split content-type #"[;=]")
      (map str/trim)
      (drop-while #(not= % "charset"))
      second)))

(defn http-content-length [^HttpMessage msg]
  (when-let [content-length (.getHeader msg "Content-Length")]
    (try
      (Integer/parseInt content-length)
      (catch Exception e
        (log/error e (str "Error parsing content-length: " content-length))
        nil))))

(defn request-uri [^HttpRequest request]
  (first (str/split (.getUri request) #"[?]")))

(defn request-query-string [^HttpRequest request]
  (second (str/split (.getUri request) #"[?]")))

;;;

(defn normalize-headers [headers]
  (zipmap
    (map
      #(->> (str/split (name %) #"-")
         (map str/capitalize)
         (str/join "-"))
      (keys headers))
    (vals headers)))

(defn guess-body-format [m]
  (let [body (:body m)]
    (when body
      (cond
        (string? body)
        ["text/plain" "utf-8"]

        (instance? File body)
        [(or
           (URLConnection/guessContentTypeFromName (.getName ^File body))
           "application/octet-stream")]

        (formats/bytes? body)
        ["application/octet-stream"]))))

(defn normalize-ring-map [m]
  (let [[type encoding] (guess-body-format m)]
    (-> m
      (update-in [:status] #(or % 200))
      (update-in [:headers] normalize-headers)
      (update-in [:headers "Connection"]
        #(or %
           (if (:keep-alive? m)
             "keep-alive"
             "close")))
      (update-in [:content-type] #(or % type))
      (update-in [:character-encoding] #(or % encoding))
      (update-in [:headers "Content-Type"]
        #(or %
           (when (:body m)
             (str
               (:content-type m)
               (when-let [charset (:character-encoding m)]
                 (str "; charset=" charset)))))))))

;;;

(defn decode-body [^String content-type ^String character-encoding body]
  (when body
    (let [charset (or character-encoding (options/charset))]
      (cond
        (.startsWith content-type "text/")
        (formats/bytes->string body charset)

        (.startsWith content-type "application/json")
        (formats/decode-json body)

        (.startsWith content-type "application/xml")
        (formats/decode-xml body)

        :else
        body))))

(defn decode-message [{:keys [content-type character-encoding body] :as msg}]
  (if (channel? body)
    (run-pipeline (reduce* conj [] body)
      #(decode-message (assoc msg :body %)))
    (assoc msg :body (decode-body content-type character-encoding body))))

;;;

(defn expand-writes [f honor-keep-alive? ch]
  (let [ch* (channel)
        default-charset (options/charset)]
    
    (bridge-join ch ch* "aleph.http.core/expand-writes"
      (fn [m]
        (let [{:keys [msg chunks write-callback]} (f m)
              result (enqueue ch* msg)
              final-stage (fn [_]
                            (when write-callback
                              (write-callback))
                            (if (and honor-keep-alive? (not (:keep-alive? m)))
                              (close ch*)
                              true))]

          (if-not chunks

            ;; non-streaming response
            (run-pipeline result
              final-stage)

            ;; streaming response
            (let [callback #(close chunks)]

              (on-closed ch callback)

              (run-pipeline nil
                {:error-handler (fn [_])}
                (fn [_]
                  (siphon
                    (map*
                      #(DefaultHttpChunk.
                         (formats/bytes->channel-buffer %
                           (or (:character-encoding m) default-charset)))
                      chunks)
                    ch*)
                  (drained-result chunks))
                (fn [_]
                  (cancel-callback ch callback)
                  (enqueue ch* HttpChunk/LAST_CHUNK))
                final-stage))))))
    ch*))

(defn collapse-reads [netty-channel ch]
  (let [executor (options/executor)
        ch* (channel)
        current-stream (atom nil)]
    (bridge-join ch ch* "aleph.http.core/collapse-reads"
      (fn [msg]
        (if (instance? HttpMessage msg)

          ;; headers
          (if-not (.isChunked ^HttpMessage msg)
            (enqueue ch* {:msg msg})
            (let [chunks (netty/wrap-network-channel netty-channel (channel))]
              (reset! current-stream chunks)
              (enqueue ch*
                {:msg msg,
                 :chunks chunks})))

          ;; chunk
          (if (.isLast ^HttpChunk msg)
            (close @current-stream)
            (enqueue @current-stream msg)))))
    ch*))

;;;

(def-derived-map RequestMap
  [^HttpRequest netty-request
   ^Channel netty-channel
   headers
   body]
  
  :scheme :http
  :keep-alive? (HttpHeaders/isKeepAlive netty-request)
  :remote-addr (netty/channel-remote-host-address netty-channel)
  :server-name (netty/channel-local-host-name netty-channel)
  :server-port (netty/channel-local-port netty-channel)
  :request-method (request-method netty-request)
  :headers @headers
  :content-type (http-content-type netty-request)
  :character-encoding (http-character-encoding netty-request)
  :uri (request-uri netty-request)
  :query-string (request-query-string netty-request)
  :content-length (http-content-length netty-request)
  :body body)

(defn netty-request->ring-map
  ([req]
     (netty-request->ring-map req (netty/current-channel)))
  ([{netty-request :msg, chunks :chunks} netty-channel]
     (->RequestMap
       netty-request
       netty-channel
       (delay (http-headers netty-request))
       (if chunks
         (map* #(.getContent ^HttpChunk %) chunks)
         (let [content (.getContent ^HttpMessage netty-request)]
           (when (pos? (.readableBytes content))
             content))))))

;;;

(def-derived-map ResponseMap
  [^HttpRequest netty-response
   headers
   body]
  
  :keep-alive? (HttpHeaders/isKeepAlive netty-response)
  :headers @headers
  :character-encoding (http-character-encoding netty-response)
  :content-type (http-content-type netty-response)
  :content-length (http-content-length netty-response)
  :status (response-code netty-response)
  :body body)

(defn netty-response->ring-map [{netty-response :msg, chunks :chunks}]
  (->ResponseMap
    netty-response
    (delay (http-headers netty-response))
    (if chunks
      (map* #(.getContent ^HttpChunk %) chunks)
      (let [content (.getContent ^HttpMessage netty-response)]
        (when (pos? (.readableBytes content))
          content)))))

(defn populate-netty-msg [m ^HttpMessage msg]
  (let [body (:body m)]

    ;; populate headers
    (doseq [[k v] (:headers m)]
      (when v
        (if (string? v)
          (.addHeader msg k v)
          (doseq [x v]
            (.addHeader msg k x)))))

    ;; populate body
    (cond

      (channel? body)
      (do
        (.setHeader msg "Transfer-Encoding" "chunked")
        {:msg msg
         :chunks body})

      (instance? InputStream body)
      (do
        (.setHeader msg "Transfer-Encoding" "chunked")
        {:msg msg
         :chunks (formats/input-stream->channel body)
         :write-callback #(.close ^InputStream body)})

      (instance? File body)
      (let [fc (.getChannel (RandomAccessFile. ^File body "r"))
            buf (ByteBuffer/allocate (.size fc))]
        (.read fc buf)
        (.rewind buf)
        (.setContent msg (ChannelBuffers/wrappedBuffer buf))
        (HttpHeaders/setContentLength msg (.size fc))
        {:msg msg
         :write-callback #(.close fc)})

      :else
      (do
        (when body
          (let [encode #(formats/bytes->channel-buffer %
                          (or (:character-encoding m) (options/charset)))
                body (if (coll? body)
                       (->> body
                         (map str)
                         (map encode)
                         encode)
                       (encode body))]
            (.setContent msg body))
          (HttpHeaders/setContentLength msg (.readableBytes (.getContent msg))))
        {:msg msg}))))

(defn valid-ring-response? [rsp]
  (contains? rsp :status))

(defn ring-map->netty-response [m]
  (let [m (if-not (valid-ring-response? m)
            (do
              (log/error "Invalid HTTP response:" (pr-str m))
              {:status 500})
            m)
        m (normalize-ring-map m)
        m (update-in m [:body] #(if (nil? %) "" %))
        response (DefaultHttpResponse.
                   HttpVersion/HTTP_1_1
                   (HttpResponseStatus/valueOf (:status m)))]
    (.setHeader response "Server" "aleph/0.3.0")
    (.setHeader response "Date" (rfc-1123-date-string))
    (populate-netty-msg m response)))

(defn elide-port? [m]
  (let [port (:port m)
        scheme (:scheme m)]
    (or
      (not port)
      (and (= "http" scheme) (= 80 port))
      (and (= "https" scheme) (= 443 port)))))

(defn valid-ring-request? [rsp]
  (contains? rsp :request-method))

(defn ring-map->netty-request [m]
  (when-not (valid-ring-request? m)
    (throw (IllegalArgumentException. (str "Invalid HTTP request: " (pr-str m)))))
  (let [m (normalize-ring-map m)
        request (DefaultHttpRequest.
                  HttpVersion/HTTP_1_1
                  (-> m :request-method keyword->netty-method)
                  (str
                    (if (empty? (:uri m))
                      "/"
                      (:uri m))
                    (when-not (empty? (:query-string m))
                      (str "?" (:query-string m)))))
        port (:port m)
        server-name (:server-name m)]
    (.setHeader request "Host"
      (str server-name
        (when-not (elide-port? m)
          (str ":" port))))
    (populate-netty-msg m request)))

;;;
