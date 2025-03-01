(ns aleph.http-test
  (:require
    [aleph.flow :as flow]
    [aleph.http :as http]
    [aleph.http.core :as http.core]
    [aleph.netty :as netty]
    [aleph.resource-leak-detector]
    [aleph.ssl :as test-ssl]
    [aleph.tcp :as tcp]
    [aleph.testutils :refer [str=]]
    [clj-commons.byte-streams :as bs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [manifold.time :as t])
  (:import
    (aleph.utils
      ConnectionTimeoutException
      ProxyConnectionTimeoutException
      RequestCancellationException
      RequestTimeoutException)
    (clojure.lang
      ExceptionInfo)
    (com.aayushatharva.brotli4j.decoder BrotliInputStream)
    (com.github.luben.zstd ZstdInputStream)
    (io.aleph.dirigiste
      IPool
      Pool)
    (io.netty.channel
      ChannelHandlerContext
      ChannelOutboundHandlerAdapter
      ChannelPipeline
      ChannelPromise)
    (io.netty.handler.codec TooLongFrameException)
    (io.netty.handler.codec.compression
      CompressionOptions
      StandardCompressionOptions)
    (io.netty.handler.codec.http
      HttpMessage
      HttpObjectAggregator
      HttpRequestDecoder
      LastHttpContent)
    (io.netty.handler.codec.http2 Http2HeadersFrame)
    (io.netty.handler.proxy ProxyConnectException)
    (java.io
      Closeable
      File)
    (java.net UnknownHostException)
    (java.util.concurrent
      SynchronousQueue
      ThreadPoolExecutor
      ThreadPoolExecutor$AbortPolicy
      TimeUnit
      TimeoutException)
    (java.util.zip
      GZIPInputStream
      InflaterInputStream)
    (javax.net.ssl
      SSLHandshakeException
      SSLSession)))

;;;

(set! *warn-on-reflection* false)

(def ^:dynamic ^IPool *pool* nil)
(def ^:dynamic *connection-options* nil)
(def ^:dynamic ^String *response* nil)
(def ^:dynamic *use-tls-requests* false)


(def port 8082)

(defn default-request-options []
  {:pool-timeout      1e4
   :request-timeout   1e4
   :throw-exceptions? false})

(defn- make-url
  [path]
  (if *use-tls-requests*
    (str "https://localhost:" port path)
    (str "http://localhost:" port path)))

(defn http-get
  ([path]
   (http-get path nil))
  ([path options]
   (http/get (make-url path) (merge (default-request-options) {:pool *pool*} options))))

(defn http-post
  ([path]
   (http-post path nil))
  ([path options]
   (http/post (make-url path) (merge (default-request-options) {:pool *pool*} options))))

(defn http-put
  ([path]
   (http-put path nil))
  ([path options]
   (http/put (make-url path) (merge (default-request-options) {:pool *pool*} options))))

(defn http-trace
  ([path]
   (http-trace path nil))
  ([path options]
   (http/trace (make-url path) (merge (default-request-options) {:pool *pool*} options))))

(def ^String filepath (str (System/getProperty "user.dir") "/test/file.txt"))

(def string-response "String!")
(def seq-response (map identity ["sequence: " 1 " two " 3.0]))
(def file-response (File. filepath))
(def http-file-response (http/file filepath))
(def http-file-region-response (http/file filepath 5 4))
(def stream-response "Stream!")

(defn string-handler [_request]
  {:status 200
   :body   string-response})

(defn seq-handler [_request]
  {:status 200
   :body   seq-response})

(defn file-handler [_request]
  {:status 200
   :body   file-response})

(defn http-file-handler [_request]
  {:status 200
   :body   http-file-response})

(defn http-file-region-handler [_request]
  {:status 200
   :body   http-file-region-response})

(defn stream-handler [_request]
  {:status 200
   :body   (bs/to-input-stream stream-response)})

(defn slow-handler [_request]
  (Thread/sleep 500)
  {:status 200
   :body   "slow"})

(defn manifold-handler [_request]
  {:status 200
   :body   (->> stream-response (map str) s/->source)})

(defn echo-handler [request]
  {:status 200
   :body   (:body request)})

(defn line-echo-handler [request]
  {:status 200
   :body   (->> request :body bs/to-line-seq)})

(defn hello-handler [_request]
  {:status 200
   :body   "hello"})

(defn invalid-handler [_]
  {:status -100
   :body   "hello"})

(defn big-handler [_]
  {:status 200
   :body   (->> (s/periodically 0.1 #(byte-array 1024))
                (s/transform (take (long 1e3))))})

(defn redirect-handler [{:keys [query-string] :as request}]
  (let [count (-> (.split #"[?=]" query-string) second Integer/parseInt)
        host (-> request :headers (get "host"))]
    (if (zero? count)
      {:status 200 :body "ok"}
      {:status  302
       :headers {"location" (str (if *use-tls-requests* "https://" "http://")
                                 "localhost:" port
                                 "/redirect?count=" (dec count))}
       :body    "redirected!"})))

(defn text-plain-handler [_]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    "Hello"})

(def latch (promise))
(def browser-server (atom nil))

(def route-map
  {"/big"            big-handler
   "/redirect"       redirect-handler
   "/stream"         stream-handler
   "/slow"           slow-handler
   "/file"           file-handler
   "/httpfile"       http-file-handler
   "/httpfileregion" http-file-region-handler
   "/manifold"       manifold-handler
   "/seq"            seq-handler
   "/string"         string-handler
   "/echo"           echo-handler
   "/line_echo"      line-echo-handler
   "/invalid"        invalid-handler
   "/text_plain"     text-plain-handler
   "/stop"           (fn [_]
                       (try
                         (deliver latch true)               ;;this can be triggered more than once, sometimes
                         (.close ^Closeable @browser-server)
                         (catch Exception _)))})

#_(defn print-vals [& args]
    (apply prn args)
    (last args))

(defn basic-handler [request]
  ((route-map (:uri request)) request))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "manifold" stream-response
     "file" "this is a file"
     "httpfile" "this is a file"
     "httpfileregion" "is a"
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))


(def http-server-options {:port port
                          :shutdown-timeout 0})
(def http1-ssl-server-options (assoc http-server-options
                                     :http-versions [:http1]
                                     :ssl-context test-ssl/server-ssl-context-opts))
(def http2-server-options (assoc http-server-options
                                 :http-versions [:http2]
                                 :ssl-context test-ssl/server-ssl-context-opts))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (binding [*pool* (http/connection-pool
                        {:connection-options
                         (merge {:insecure? true
                                 :http-versions [:http2 :http1]}
                                *connection-options*)})]
       (try
         ~@body
         (finally
           (.close ^Closeable server#)
           (netty/wait-for-close server#)
           (.shutdown *pool*))))))

(defmacro with-http1-server
  [handler server-options & body]
  `(testing "- http1"
     (with-server (http/start-server ~handler (merge http-server-options ~server-options))
       ~@body)))

(defmacro with-http2-server
  [handler server-options & body]
  ;; with-redefs used so clj fns running on netty threads will work
  `(testing "- http2"
     (with-redefs [*use-tls-requests* true]
       (with-server (http/start-server ~handler (merge http2-server-options ~server-options))
         ~@body))))

(defmacro with-http-servers
  "Run the same body of tests for each HTTP version"
  [handler server-options & body]
  `(let [handler# ~handler
         server-options# ~server-options]
     (with-http2-server handler# server-options# ~@body)
     (with-http1-server handler# server-options# ~@body)))

(defmacro with-http-ssl-servers
  "Run the same body of tests for each HTTP version with SSL enabled"
  [handler server-options & body]
  `(with-redefs [*use-tls-requests* true]
     (let [handler# ~handler
           server-options# ~server-options]
       (with-http2-server handler# server-options# ~@body)
       (with-http1-server handler# (merge http1-ssl-server-options server-options#) ~@body))))

(defmacro with-handler [handler & body]
  `(with-http-servers ~handler {}
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-http-servers ~handler {:raw-stream? true}
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (testing "cooked"
       (with-handler ~handler ~@body))
     (testing "raw"
       (with-raw-handler ~handler ~@body))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[path result] expected-results]
      (is (= result
             (bs/to-string
               (:body
                 @(http-get (str "/" path)))))))))

(deftest test-compressed-response
  (let [long-ass-timeout 600000
        expected-results
        [["string" string-response]
         ["stream" stream-response]
         ["manifold" stream-response]
         ["file" "this is a file"]
         ["httpfile" "this is a file"]
         ["httpfileregion" "is a"]
         ["seq" (apply str seq-response)]]]
    (binding [*connection-options* {:connection-timeout long-ass-timeout}]

      (doseq [{:keys [phrase opts encs]}
              [{:phrase "All"
                :opts   {:compression?     true
                         :idle-timeout     600000}
                :encs   [{:accept-encoding  "gzip"
                          :content-encoding "gzip"
                          :stream-ctor      #(GZIPInputStream. %)}
                         {:accept-encoding  "deflate"
                          :content-encoding "deflate"
                          :stream-ctor      #(InflaterInputStream. %)}
                         {:accept-encoding  "br"
                          :content-encoding "br"
                          :stream-ctor      #(BrotliInputStream. %)}
                         {:accept-encoding  "zstd"
                          :content-encoding "zstd"
                          :stream-ctor      #(ZstdInputStream. %)}]}

               {:phrase "Gzip only"
                :opts   {:compression?        true
                         :compression-options (into-array CompressionOptions [(StandardCompressionOptions/gzip)])
                         :idle-timeout        600000}
                :encs   [{:accept-encoding  "gzip"
                          :content-encoding "gzip"
                          :stream-ctor      #(GZIPInputStream. %)}
                         {:accept-encoding  "deflate"
                          :content-encoding nil
                          :stream-ctor      identity}
                         {:accept-encoding  "br"
                          :content-encoding nil
                          :stream-ctor      identity}]}]]

        (testing phrase
          (doseq [{:keys [accept-encoding content-encoding stream-ctor]} encs]
            (testing (str " - " accept-encoding "-> " content-encoding)
              (with-http-servers basic-handler opts
                (doseq [[path result] expected-results]
                  (testing (str "/" path)
                    (let [resp @(http-get (str "/" path)
                                          {:headers         {:accept-encoding accept-encoding}
                                           :request-timeout long-ass-timeout})]
                      (is (str= content-encoding (get-in resp [:headers :content-encoding]))
                          (str "Content-encoding header should be \"" content-encoding "\""))

                      (let [body (:body resp)
                            unzipped (bs/to-string (stream-ctor body))]
                        ;; FIXME: need a TeeInputStream or some way to copy it for the below test
                        ;;(is (not (str= result body)) "Compressed body should not match original uncompressed")
                        (is (some? unzipped) "Unzipped content should not be nil.")
                        (is (str= result unzipped) "Decompressed body should match original"))))))))))

      (testing ":compression-level"
        (doseq [{:keys [phrase opts encs]}
                [{:phrase "All for :compression-level"
                  :opts   {:compression-level   3
                           :idle-timeout        600000}
                  :encs   [{:accept-encoding  "gzip"
                            :content-encoding "gzip"
                            :stream-ctor      #(GZIPInputStream. %)}
                           {:accept-encoding  "deflate"
                            :content-encoding "deflate"
                            :stream-ctor      #(InflaterInputStream. %)}
                           {:accept-encoding  "br"
                            :content-encoding "br"
                            :stream-ctor      #(BrotliInputStream. %)}
                           {:accept-encoding  "zstd"
                            :content-encoding "zstd"
                            :stream-ctor      #(ZstdInputStream. %)}]}]]
          (testing phrase
            (doseq [{:keys [accept-encoding content-encoding stream-ctor]} encs]
              (testing (str " - " accept-encoding "-> " content-encoding)
                (with-http1-server basic-handler opts
                  (doseq [[path result] expected-results]
                    (testing (str "/" path)
                      (let [resp @(http-get (str "/" path)
                                            {:headers         {:accept-encoding accept-encoding}
                                             :request-timeout long-ass-timeout})]
                        (is (str= content-encoding (get-in resp [:headers :content-encoding]))
                            (str "Content-encoding header should be \"" content-encoding "\""))

                        (let [body (:body resp)
                              unzipped (bs/to-string (stream-ctor body))]
                          ;; FIXME: need a TeeInputStream or some way to copy it for the below test
                          ;;(is (not (str= result body)) "Compressed body should not match original uncompressed")
                          (is (some? unzipped) "Unzipped content should not be nil.")
                          (is (str= result unzipped) "Decompressed body should match original"))))))))))))))


(deftest test-ssl-response-formats
  (with-http-ssl-servers basic-handler {}
    (doseq [[path result] expected-results]
      (is
       (= result
          (bs/to-string
           (:body
            @(http-get (str "/" path)))))
       (str path "path failed")))))

(deftest test-files
  (with-handler echo-handler
    (is (-> @(http-put "/" {:body (io/file "test/empty.txt")})
            :body
            bs/to-string
            str/blank?))
    (is (= (slurp "test/file.txt" :encoding "UTF-8")
           (-> @(http-put "/" {:body (io/file "test/file.txt")})
               :body
               bs/to-string)))))

(deftest test-ssl-files
  (let [client-path "/"
        client-options {:connection-options {:ssl-context test-ssl/client-ssl-context-opts}}
        client-pool (http/connection-pool client-options)]
    (with-http-ssl-servers echo-handler {}
      (is (str/blank?
           (-> @(http-put client-path
                          {:body (io/file "test/empty.txt")
                           :pool client-pool})
               :body
               bs/to-string)))
      (is (= (slurp "test/file.txt" :encoding "UTF-8")
             (-> @(http-put client-path
                            {:body (io/file "test/file.txt")
                             :pool client-pool})
                 :body
                 bs/to-string))))))

(deftest using-input-stream-as-ssl-context-trust-store
  (let [num-requests 2
        file-name "test/ca_cert.pem"
        client-options (fn [stream]
                         {:connection-options {:ssl-context {:private-key test-ssl/client-key
                                                             :certificate-chain [test-ssl/client-cert]
                                                             :trust-store stream}}})
        requests (fn [pool]
                   (repeatedly num-requests #(http-post "/"
                                                        {:body "hello!"
                                                         :pool pool})))]
    (testing "multiple serial requests without connection reuse"
      (with-open [stream (io/input-stream file-name)]
        (let [client-pool (http/connection-pool (-> (client-options stream)
                                                    (assoc-in [:connection-options :keep-alive?] false)))]
          (with-http-ssl-servers echo-handler {}
            (is (every?
                  #{"hello!"}
                  (->> (requests client-pool)
                       (mapv (comp bs/to-string :body deref)))))))))

    (testing "multiple concurrent requests"
      (with-open [stream (io/input-stream file-name)]
        (let [client-pool (http/connection-pool (client-options stream))]
          (with-http-ssl-servers echo-handler {}
            (is (every?
                  #{"hello!"}
                  (->> (requests client-pool)
                       (doall)
                       (mapv (comp bs/to-string :body deref)))))))))))

(defn ssl-session-capture-handler [ssl-session-atom]
  (fn [req]
    (reset! ssl-session-atom (http.core/ring-request-ssl-session req))
    {:status 200 :body "ok"}))

(deftest test-ssl-session-access
  (let [ssl-session (atom nil)]
    (with-http-ssl-servers (ssl-session-capture-handler ssl-session) {}
      (reset! ssl-session nil)
      (is (= 200 (:status @(http-get "/"))))
      (is (some? @ssl-session))
      (when-let [^SSLSession s @ssl-session]
        (is (.isValid s))
        (is (not (str/includes? "NULL" (.getCipherSuite s))))))))

(deftest test-ssl-with-plain-client-request
  (let [ssl-session (atom nil)]
    (with-http-ssl-servers (ssl-session-capture-handler ssl-session) {}
      (reset! ssl-session nil)
      (with-redefs [*use-tls-requests* false] ; will make http-get use http instead of https
        (is (some-> (http-get "/")
                    (d/catch identity)
                    deref
                    ex-message
                    (str/includes? "connection was closed"))))
      (is (nil? @ssl-session)))))

(deftest test-ssl-endpoint-identification
  (binding [*connection-options* {:insecure?   false
                                  :ssl-context test-ssl/wrong-hostname-client-ssl-context-opts}]
    (let [ssl-session (atom nil)]
      (with-http-ssl-servers
          (ssl-session-capture-handler ssl-session)
          {:ssl-context test-ssl/wrong-hostname-server-ssl-context-opts}
        (reset! ssl-session nil)
        (try
          @(http-get "/")
          (is (= true false) "Should have thrown an exception")

          (catch Exception e
            (is (= SSLHandshakeException
                   (class e)))

            ;; Should have a hostname mismatch cause in the ex chain
            (is (loop [^Exception ex e]
                  (if ex
                    (if (re-find #"(?i:No name matching localhost found)"
                                 (.getMessage ex))
                      true
                      (recur (.getCause ex)))
                    false))
                "No hostname mismatch cause found in exception chain")))
        (is (nil? @ssl-session))))))

(deftest test-disabling-ssl-endpoint-identification
  (binding [*connection-options* {:insecure?           false
                                  :ssl-context         test-ssl/wrong-hostname-client-ssl-context-opts
                                  :ssl-endpoint-id-alg nil}]
    (let [ssl-session (atom nil)]
      (with-http-ssl-servers
          (ssl-session-capture-handler ssl-session)
          {:ssl-context test-ssl/wrong-hostname-server-ssl-context-opts}
        (reset! ssl-session nil)
        (is (= 200 (:status @(http-get "/"))))
        (is (some? @ssl-session))))))

(deftest test-invalid-body
  (let [client-url "/"]
    (with-handler echo-handler
      (is (thrown? Exception
                   @(http-put client-url
                              {:body 123}))))))

(def characters
  (let [charset (conj (mapv char (range 32 127)) \newline)]
    (repeatedly #(rand-nth charset))))

(deftest test-bulk-requests
  (let [pool (http/connection-pool {})]
    (with-handler basic-handler
      (->> (range 1e2)
           (map (fn [_] (http-get "/string") {:pool pool}))
           (apply d/zip)
           deref)
      (dotimes [_ 10]
        (->> (range 10)
             (map (fn [_] (http-get "/string") {:pool pool}))
             (apply d/zip)
             deref)))))

(deftest test-overly-long-url
  (let [long-url (apply str "/" (repeat (long 1e5) "a"))]
    (with-http1-server basic-handler http-server-options
      (is (= 414 (:status @(http-get long-url)))))))

(deftest test-overly-long-header
  (let [url "/"
        long-header-value (apply str (repeat (long 1e5) "a"))
        opts {:headers {"X-Long" long-header-value}}]
    (with-http1-server basic-handler http-server-options
      (is (= 431 (:status @(http-get url opts)))))))

(deftest test-invalid-http-version-format
  (with-http1-server basic-handler http-server-options
    (let [client @(tcp/client {:host "localhost" :port port})
          _ @(s/put! client "GET / HTTP-1,1\r\n\r\n")
          response (bs/to-string @(s/take! client))]
      (is (str/starts-with? response "HTTP/1.1 400"))
      (s/close! client))))

(deftest test-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5 1e6 1e7]]
      (let [characters (->> characters (take len) (apply str))
            body (:body
                   @(http-put "/echo"
                              {:body characters}))
            body' (bs/to-string body)]
        (assert (== (min (count characters) len) (count body')))
        (is (= characters body'))))))

(deftest test-redirect
  (testing "basic redirecting"
    (with-both-handlers basic-handler
      (is (= "ok"
             (-> @(http-get "/redirect?count=10")
                 :body
                 bs/to-string)))
      (is (= "redirected!"
             (-> @(http-get "/redirect?count=25")
                 :body
                 bs/to-string)))
      (is (= "ok"
             (-> @(http-get "/redirect?count=25"
                            {:max-redirects 30})
                 :body
                 bs/to-string)))))


  (testing "works with :method as well as :request-method"
    (with-handler
      (fn [{:keys [uri]}]
        (case uri
          "/200" {:status 200}
          "/301" {:status  301
                  :headers {"Location" "/200"}}))
      (is (= 200 (:status @(http/request
                             (merge (default-request-options)
                                    {:method            :get
                                     :url               (make-url "/301")
                                     :follow-redirects? true
                                     :pool              *pool*}))))))))

(deftest test-middleware
  (with-both-handlers basic-handler
    (is (= "String!"
           (-> @(http-get "/stream"
                          {:middleware
                           (fn [client]
                             (fn [req]
                               (client (assoc req :url (make-url "/string")))))})
               :body
               bs/to-string)))))

(deftest test-line-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5]]
      (let [characters (->> characters (take len) (apply str))
            body (:body
                   @(http-put "/line_echo"
                              {:body characters}))]
        (is (= (.replace ^String characters "\n" "") (bs/to-string body)))))))

(deftest test-illegal-character-in-url
  (with-handler hello-handler
    (is (= "hello"
           (-> @(http-get "/?param=illegal character")
               :body
               bs/to-string)))))

(deftest test-connection-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
                 @(http/get "http://192.0.2.0"              ;; "TEST-NET" in RFC 5737
                            (merge (default-request-options) {:pool *pool* :connection-timeout 2})))))

  (with-handler basic-handler
    (is (thrown? ConnectionTimeoutException
                 @(http/get "http://192.0.2.0"              ;; "TEST-NET" in RFC 5737
                            (merge (default-request-options) {:pool *pool* :connection-timeout 2}))))))


(deftest test-pool-connect-timeout
  (binding [*connection-options* {:connect-timeout 2}]
    (with-handler basic-handler
      (is (thrown? ConnectionTimeoutException
                   (deref (http/get "http://192.0.2.0" ;; "TEST-NET" in RFC 5737
                                    (merge (default-request-options) {:pool *pool*
                                                                      :connection-timeout 500}))
                          1e3
                          :timeout))))))

(deftest test-request-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
                 @(http-get "/slow"
                            {:request-timeout 5}))))

  (with-handler basic-handler
    (is (thrown? RequestTimeoutException
                 @(http-get "/slow"
                            {:request-timeout 5})))))

(deftest test-explicit-url
  (with-handler hello-handler
    (is (= "hello" (-> @(http/request {:method          :get
                                       :scheme          (if *use-tls-requests* :https :http)
                                       :server-name     "localhost"
                                       :server-port     port
                                       :request-timeout 1e3
                                       :pool            *pool*})
                       :body
                       bs/to-string)))))

(deftest test-debug-middleware
  (with-handler hello-handler
    (let [url "/"
          r1 @(http-get url {:query-params  {:name "John"}
                             :save-request? true
                             :debug-body?   true})
          r2 @(http-get url {:save-request? true
                             :debug-body?   false})]
      (is (contains? r1 :aleph/request))
      (is (= "name=John" (get-in r1 [:aleph/request :query-string])))
      (is (contains? r1 :aleph/netty-request))
      (is (contains? r1 :aleph/request-body))
      (is (not (contains? r2 :aleph/request-body))))))

(deftest test-response-executor-affinity
  (let [pool (http/connection-pool {})
        ex (flow/fixed-thread-executor 4)]
    (with-handler hello-handler
      @(d/future-with ex
         (let [rsp (http-get "/" {:connection-pool pool})]
           (is (= http/default-response-executor (.executor rsp))))))))

(deftest test-trace-request-omitted-body
  (with-handler echo-handler
    (is (= "" (-> @(http-trace "/" {:body "REQUEST"})
                  :body
                  bs/to-string)))))

(deftest test-invalid-response
  (with-handler invalid-handler
    (let [{:keys [body status]} @(http-get "/invalid")]
      (is (= 500 status))
      (is (re-find #"Internal Server Error"
                   (bs/to-string body)))))
  (testing "custom error handler"
    (with-http-servers invalid-handler
                       {:error-handler    (fn [_]
                                            {:status 500
                                             :body   "Internal error"})}
                       (let [{:keys [body status]} @(http-get "/invalid")]
                         (is (= 500 status))
                         (is (= "Internal error"
                                (bs/to-string body)))))))

;;;

(defn get-netty-client-event-threads []
  (->> (Thread/getAllStackTraces)
       .keySet
       vec
       (filter #(.startsWith ^String (.getName ^Thread %) netty/client-event-thread-pool-name))))

(deftest test-client-events-daemon-thread
  (with-handler basic-handler
    (is
      (= string-response
         (bs/to-string
           (:body
             @(http-get "/string")))))
    (let [client-threads (get-netty-client-event-threads)]
      (is (> (count client-threads) 0))
      (is (every? #(.isDaemon ^Thread %) client-threads)))))

(def ^String netty-threads-property-name "io.netty.eventLoopThreads")

(deftest test-default-event-loop-threads
  (let [initial-threads-property (System/getProperty netty-threads-property-name)
        set-prop #(System/setProperty netty-threads-property-name (str %))
        clear-prop #(System/clearProperty netty-threads-property-name)]

    (testing "Default event thread count is twice the cpu count."
      (clear-prop)
      (let [default-threads (netty/get-default-event-loop-threads)]
        (is (= default-threads (->> (Runtime/getRuntime) (.availableProcessors) (* 2))))
        (testing "Netty eventLoopThreads property overrides default thread count"
          (let [property-threads (inc default-threads)]
            (set-prop property-threads)
            (is (= property-threads (netty/get-default-event-loop-threads)))
            (clear-prop)))))

    (testing "Event thread count minimum is 1."
      (set-prop 0)
      (is (= 1 (netty/get-default-event-loop-threads)))
      (clear-prop))
    (if initial-threads-property
      (set-prop initial-threads-property)
      (clear-prop))))

(defn- slow-stream
  "Produces a `manifold.stream` which yield a value
  every 50 milliseconds six times."
  []
  (let [body (s/stream 10)]
    (-> (d/loop [cnt 0]
          (t/in 50.0
                (fn []
                  (d/chain' (s/put! body (str cnt))
                            (fn [_]
                              (when (< cnt 5)
                                (d/recur (inc cnt))))))))
        (d/chain' (fn [_] (s/close! body))))
    body))

(defn- force-stream-to-string-memoization! []
  (bs/to-string (doto (s/stream 1)
                      (s/put! "x")
                      s/close!)))

(defn echo-string-handler [{:keys [body]}]
  {:status 200
   :body   (when body ; cooked handler produces nil for empty request body
             (bs/to-string (if (s/stream? body)
                             (s/map netty/release-buf->array body)
                             body)))})

(deftest test-idle-timeout
  ;; Required so that the conversion initialization doesn't count
  ;; toward the idle timeout. See
  ;; https://github.com/clj-commons/aleph/issues/626 for background.
  (force-stream-to-string-memoization!)

  (let [path "/"
        slow-handler (fn [_] {:body (slow-stream)})]
    (testing "Server is slow to write, but has time"
      (with-http-servers slow-handler {:idle-timeout 200}
        (is (= "012345" (bs/to-string (:body @(http-get path)))))))
    (testing "Server is too slow to write"
      (with-http-servers slow-handler {:idle-timeout 30}
        (is (= ""
               (bs/to-string (:body @(http-get path)))))))
    (testing "Client is slow to write, but has time"
      (with-http-servers echo-string-handler {:idle-timeout 200}
        (is (= "012345" (bs/to-string (:body @(http-put path {:body (slow-stream)})))))))
    (testing "Client is too slow to write"
      (with-http-servers echo-string-handler {:idle-timeout 30}
        (is (thrown-with-msg? Exception #"connection was close"
                              (bs/to-string (:body @(http-put path {:body (slow-stream)})))))))))

(deftest test-empty-request-body
  (with-both-handlers echo-string-handler
    (is (= "" (bs/to-string (:body @(http-put "/" {:body ""})))))))

;;;

(deftest test-large-responses
  (with-handler basic-handler
    (dotimes [_ 1 #_1e6]
      #_(when (zero? (rem i 1e2))
          (prn i))
      (-> @(http-get "/big"
                     {:as :byte-array})
          :body
          count))))

;;;

(deftest ^:benchmark benchmark-http
  (println "starting HTTP benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server hello-handler {:port 8080 :shutdown-timeout 0})]
    (Thread/sleep (* 1000 60))
    (println "stopping server")
    (.close ^Closeable server)))

(deftest ^:benchmark benchmark-websockets
  (println "starting WebSocket benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server
                 (fn [req]
                   (d/let-flow [s (http/websocket-connection req)]
                     (s/consume #(s/put! s %) s)))
                 {:port             8080
                  :shutdown-timeout 0})]
    (Thread/sleep (* 1000 60))
    (println "stopping server")
    (.close ^Closeable server)))


(deftest test-pipeline-header-alteration
  (let [test-header-name "aleph-test"
        test-header-val "MOOP"]
    (with-http1-server basic-handler {:pipeline-transform
                                      (fn [^ChannelPipeline pipeline]
                                        (.addBefore pipeline
                                                    "request-handler"
                                                    "test-header-inserter"
                                                    (proxy [ChannelOutboundHandlerAdapter] []
                                                      (write [^ChannelHandlerContext ctx
                                                              ^Object msg
                                                              ^ChannelPromise p]
                                                        (when (instance? HttpMessage msg)
                                                          (let [^HttpMessage http-msg msg]
                                                            (-> http-msg
                                                                (.headers)
                                                                (.set test-header-name test-header-val))))
                                                        (.write ctx msg p)))))}
      (let [resp @(http-get "/string")]
        (is (= test-header-val (get (:headers resp) test-header-name)))))))

(deftest test-pipeline-transforms
  (let [header-name "aleph-foo"
        foo-header-adder
        (netty/channel-inbound-handler
          :channel-read
          ([_ ctx msg]
           (cond
             (instance? HttpMessage msg)
             (let [^HttpMessage http-msg msg]
               (-> http-msg
                   (.headers)
                   (.set header-name "foo")))

             (instance? Http2HeadersFrame msg)
             (let [h2-headers-frame ^Http2HeadersFrame msg]
               (-> h2-headers-frame
                   (.headers)
                   (.add header-name "foo"))))

           (.fireChannelRead ctx msg)))

        create-handler
        (fn [found?]
          (fn [request]
            (reset! found? (-> request :headers (contains? header-name)))
            {:status 200
             :body   "moop"}))

        http1-test
        (fn [base-handler-name xform-key expected-val]
          (testing (str " - :" xform-key)
            (let [found? (atom nil)]
              (with-http1-server (create-handler found?)
                (assoc http-server-options
                       xform-key #(.addBefore % base-handler-name "foo-adder" foo-header-adder))
                @(http-get "/")

                (is (= expected-val @found?))))))

        http2-test
        (fn [base-handler-name xform-key expected-val]
          (testing (str " - " xform-key)
            (let [found? (atom nil)]
              (with-http2-server
                (create-handler found?)
                (assoc http2-server-options
                       xform-key #(.addBefore % base-handler-name "foo-adder" foo-header-adder))

                @(http-get "/")
                (is (= expected-val @found?))))))]


    (testing "server"
      (testing " - http1 only"
        (let [base-handler-name "request-handler"]
          (http1-test base-handler-name :pipeline-transform true)
          (http1-test base-handler-name :http1-pipeline-transform true)
          (http1-test base-handler-name :http2-stream-pipeline-transform false)
          (http1-test base-handler-name :http2-conn-pipeline-transform false)))

      (testing " - http2 only"
        (testing "- :pipeline-transform"
          (is (thrown? Exception
                       (with-http2-server
                         (create-handler (atom :irrelevant))
                         (assoc http2-server-options
                                :pipeline-transform #(.addBefore % "request-handler" "foo-adder" foo-header-adder))

                         @(http-get "/")
                         ;; should never get here
                         (is (= true false))))))

        (http2-test "request-handler" :http1-pipeline-transform false)
        (http2-test "handler" :http2-stream-pipeline-transform true)
        (http2-test "multiplex" :http2-conn-pipeline-transform true)

        (testing "multiple xforms"
          (is (thrown? Exception
                       (with-http2-server
                         (create-handler (atom :irrelevant))
                         (assoc http2-server-options
                                :pipeline-transform #(.addBefore % "request-handler" "foo-adder" foo-header-adder)
                                :http2-stream-pipeline-transform #(.addBefore % "handler" "foo-adder" foo-header-adder)
                                :http2-conn-pipeline-transform #(.addBefore % "handler" "foo-adder" foo-header-adder))

                         @(http-get "/")
                         ;; should never get here
                         (is (= true false))))))))))

(defn add-http-object-aggregator [^ChannelPipeline pipeline]
  (let [max-content-length 5]
    (.addBefore pipeline
                "request-handler"
                "http-object-aggregator"
                (HttpObjectAggregator. max-content-length))))

(deftest test-http-object-aggregator-support
  (with-http1-server
    basic-handler
    {:pipeline-transform add-http-object-aggregator}

    (let [rsp @(http-put "/echo"
                         {:body "hello"})]
      (is (= "hello" (bs/to-string (:body rsp))))
      (is (= 200 (:status rsp))))

    (let [rsp @(http-put "/echo"
                         {:body "hello, world!"})]
      (is (= 413 (:status rsp)))
      (is (empty? (bs/to-string (:body rsp)))))))

(deftest test-http-object-aggregator-raw-stream-support
  (with-http1-server
    basic-handler
    {:raw-stream?        true
     :pipeline-transform add-http-object-aggregator}

    (let [rsp @(http-put "/echo"
                         {:body "hello"})]
      (is (= "hello" (bs/to-string (:body rsp))))
      (is (= 200 (:status rsp))))

    (let [rsp @(http-put "/echo"
                         {:body "hello, world!"})]
      (is (= 413 (:status rsp)))
      (is (empty? (bs/to-string (:body rsp)))))))

(deftest test-transport
  (testing "epoll"
    (try
      (with-http-servers
        basic-handler
        {:transport        :epoll}

        (let [rsp @(http-put "/echo"
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :epoll
                                                                                :insecure? true}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/epoll-available?))))))

  (testing "kqueue"
    (try
      (with-http-servers
        basic-handler
        {:transport        :kqueue}

        (let [rsp @(http-put "/echo"
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :kqueue
                                                                                :insecure? true}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/kqueue-available?))))))

  (testing "io-uring"
    (try
      (with-http-servers
        basic-handler
        {:transport        :io-uring}

        (let [rsp @(http-put "/echo"
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :io-uring
                                                                                :insecure? true}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))

(deftest test-max-request-body-size
  (testing "max-request-body-size of 0"
    (with-http-servers (constantly {:body "OK" :status 200})
      {:max-request-body-size 0}
      (let [resp @(http-put "/"
                            {:body "hello"})]
        (is (= 413 (:status resp))))))

  (testing "max-request-body-size of 1"
    (with-http-servers (constantly {:body "OK" :status 200})
      {:max-request-body-size 1}
      (let [resp @(http-put "/"
                            {:body "hello"})]
        (is (= 413 (:status resp))))))

  (testing "max-request-body-size of 6"
    (with-http-servers (constantly {:body "OK" :status 200})
      {:max-request-body-size 6}
      (let [resp @(http-put "/"
                            {:body "hello"})]
        (is (= 200 (:status resp)))))))

(deftest test-text-plain-charset
  (with-http-servers
    basic-handler
    {}

    (let [resp @(http-get "/text_plain")]
      (is (= "text/plain; charset=UTF-8" (-> resp :headers (get "content-type"))))
      (is (= 200 (:status resp)))
      (is (= "Hello" (bs/to-string (:body resp)))))))

;;;
;;; errors processing
;;;

(def http-line-delimiter "\r\n")

(def http-message-delimiter (str http-line-delimiter http-line-delimiter))

(defn encode-http-object [lines]
  (str (str/join http-line-delimiter lines) http-message-delimiter))

(defn tcp-handler [response]
  (fn [s ch]
    (s/take! s)
    ;; too fast, have to slow it down - since this handler responds as soon as
    ;; it's called, a returned error can shut down the client channel before
    ;; it's even finished sending, leading to UNexpected errors
    (Thread/sleep 5)
    (s/put! s (encode-http-object response))))

(defmacro with-tcp-response [response & body]
  `(with-server (tcp/start-server (tcp-handler ~response) {:port port
                                                           :shutdown-timeout 0})
     ~@body))

(defmacro with-tcp-request-handler [handler options request & body]
  `(with-server (http/start-server ~handler (merge http-server-options ~options))
     (let [conn# @(tcp/client {:host "localhost" :port port})
           decode# (fnil bs/to-string "")]
       (s/put! conn# (encode-http-object ~request))
       (binding [*response* (-> conn# s/take! deref decode#)]
         ~@body)
       (s/close! conn#))))

(defmacro with-tcp-request [options request & body]
  `(with-tcp-request-handler echo-handler ~options ~request ~@body))


(defn invalid-response-message []
  ;; note that `HttpObjectDecoder.allowDuplicateContentLengths` is
  ;; set to `false` by default. which means that the following
  ;; response leads to `HttpObjectDecoder` generating what's
  ;; so called "Invalid Message"
  ;; https://github.com/netty/netty/blob/4.1/codec-http/src/main/java/io/netty/handler/codec/http/HttpObjectDecoder.java#L561
  (with-tcp-response ["HTTP/1.1 4000001 Super Bad Request"
                      "Server: Aleph"
                      "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                      "Content-Length: 0"
                      "Content-Length: 100"
                      "Connection: close"]
                     (is (thrown? IllegalArgumentException
                                  (-> (http-post "/" {:body "hello!"})
                                      (d/timeout! 5e3)
                                      deref)))))

(deftest test-client-errors-handling
  (testing "writing invalid request message"
    (with-handler echo-string-handler
      (is (thrown? Exception
                   (-> (http-post "/" {:body 42})
                       (d/timeout! 1e3)
                       deref)))))

  (testing "writing invalid request body"
    (with-handler echo-string-handler
      (is (thrown? Exception
                   (-> (http-post "/" {:body (s/->source [1])})
                       (d/timeout! 1e3)
                       deref)))))

  (testing "reading invalid response message"
    (binding [*connection-options* {:raw-stream? false}]
      (invalid-response-message)))

  (testing "reading invalid response message: raw stream"
    (binding [*connection-options* {:raw-stream? true}]
      (invalid-response-message)))

  (testing "reading response that violates decoder frame limit"
    (binding [*connection-options* {:max-initial-line-length 10}]
      (with-tcp-response ["HTTP/1.1 4000001 Super Bad Request"
                          "Server: Aleph"
                          "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                          "Content-Length: 0"
                          "Connection: close"]
                         (is (thrown? TooLongFrameException
                                      (-> (http-post "/" {:body "hello!"})
                                          (d/timeout! 1e3)
                                          deref))))))

  (testing "reading invalid chunked response body"
    (with-tcp-response ["HTTP/1.1 4000001 Super Bad Request"
                        "Server: Aleph"
                        "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                        "Connection: Keep-Alive"
                        "transfer-encoding: chunked"
                        ""
                        "not-a-number"                      ;; definitely not parseable chunk size
                        "fail"
                        "0"]
                       (let [rsp (-> (http-post "/")
                                     (d/timeout! 1e3)
                                     deref)]
                         (is (= 4000001 (:status rsp)))
                         (is (= "" (bs/to-string (:body rsp))))))) ;; if the body is invalid, empty is returned with the current Aleph behaviour

  (testing "response body larger then content-length"
    (binding [*connection-options* {:response-buffer-size 1
                                    :keep-alive?          true}]
      (let [long-line "hello body is too long to be true"]
        (with-tcp-response ["HTTP/1.1 200 OK"
                            "Server: Aleph"
                            "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                            "Content-Length: 1"
                            "Connection: Keep-Alive"
                            ""
                            long-line]
                           (is (= "h" (-> (http-get "/")
                                          (d/timeout! 1e3)
                                          deref
                                          :body
                                          bs/to-string)))
                           (is (= "h" (-> (http-get "/")
                                          (d/timeout! 1e3)
                                          deref
                                          :body
                                          bs/to-string)))))))

  (testing "unknown host with JDK DNS resolver"
    (is (thrown? UnknownHostException
                 (-> (http/get "http://unknown-host/")
                     (d/timeout! 1e4)
                     deref))))

  (testing "unknown host with custom DNS client"
    (let [pool (http/connection-pool
                 {:connection-options (assoc (default-request-options) :insecure? true)
                  :dns-options        {:name-servers ["1.1.1.1"]}})]
      (try
        (is (thrown? UnknownHostException
                     (-> (http/get "http://unknown-host/" {:pool pool})
                         (d/timeout! 1e4)
                         deref)))
        (finally
          (.shutdown ^Pool pool))))))

(defmacro with-rejected-handler [handler & body]
  `(let [handler# ~handler
         ^ThreadPoolExecutor executor# (ThreadPoolExecutor.
                                         1 1 0 TimeUnit/MILLISECONDS (SynchronousQueue.)
                                         (ThreadPoolExecutor$AbortPolicy.))
         d# (d/deferred)]
     (try
       (.execute executor# (fn [] @d#))
       (with-http-servers echo-handler {:executor           executor#
                                        :shutdown-executor? false
                                        :rejected-handler   handler#}
                          ~@body)
       (finally
         (d/success! d# true)
         (.shutdown executor#)))))

(deftest test-server-errors-handling
  (testing "rejected handler when accepting connection"
    (with-rejected-handler nil
      (is (= 503 (-> (http-get "/")
                     (d/timeout! 1e3)
                     deref
                     :status)))))

  (testing "custom rejected handler"
    (with-rejected-handler (fn [_] {:status 201 :body "I'm actually okayish"})
      (is (= 201 (-> (http-get "/")
                     (d/timeout! 1e3)
                     deref
                     :status)))))

  (testing "throwing exception within rejected handler"
    (with-rejected-handler (fn [_] (throw (RuntimeException. "you shall not reject!")))
      (let [resp (-> (http-get "/")
                     (d/timeout! 1e3)
                     deref)]
        (is (= 500 (:status resp)))
        (is (= "Internal Server Error" (bs/to-string (:body resp)))))))

  (testing "throwing exception within ring handler"
    (with-handler (fn [_] (throw (RuntimeException. "you shall not pass!")))
      (let [resp (-> (http-get "/")
                     (d/timeout! 1e3)
                     deref)]
        (is (= 500 (:status resp)))
        (is (= "Internal Server Error" (bs/to-string (:body resp)))))))

  (testing "reading invalid request message: bad request"
    ;; this request should fail with `IllegalArgumentException` "multiple Content-Lenght headers"
    (with-tcp-request {} ["GET / HTTP/1.1"
                          "content-length: 0"
                          "content-length: 1"]
                      (is (.startsWith ^String *response* "HTTP/1.1 400 Bad Request"))))

  (testing "reading invalid request message: URI is too long"
    (with-tcp-request
      {:max-initial-line-length 1}
      ["GET /not-very-long-uri-though HTTP/1.1"
       "content-length: 0"]
      (is (.startsWith *response* "HTTP/1.1 414 Request-URI Too Long"))))

  ;; TODO: make an H2 version
  (testing "reading invalid request message: header is too large"
    (with-tcp-request
      {:max-header-size 5}
      ["GET / HTTP/1.1"
       "content-length: 0"
       "header: value-that-is-definitely-too-large"]
      (is (.startsWith *response* "HTTP/1.1 431 Request Header Fields"))))

  (testing "reading invalid request body"
    (with-tcp-request-handler
      (fn [req]
        (try
          (bs/to-string (:body req))
          (catch Throwable _))
        {:status 201
         :body   "not good"})
      {:request-buffer-size 1}
      ["POST / HTTP/1.1"
       "transfer-encoding: chunked"
       ""
       "not-a-number"                                       ;; definitely not parseable chunk size
       "fail"
       "0"]
      (is (.startsWith *response* "HTTP/1.1 201 Created"))))

  (testing "writing invalid response message"
    (let [invalid-status-handler
          (fn [{:keys [body]}]
            (when (some? body) (bs/to-string body))
            {:status  1045
             :headers {nil "not such header"}
             :body    "there's no such status"})]
      (with-handler invalid-status-handler
        (is (= 500 (-> (http-get "/")
                       (d/timeout! 1e3)
                       deref
                       :status))))))

  (testing "writing invalid response body"
    (let [invalid-body-handler
          (fn [{:keys [body]}]
            (when (some? body) (bs/to-string body))
            {:status 200
             :body   (s/->source [1])})]
      (with-handler invalid-body-handler
        (let [resp (-> (http-get "/")
                       (d/timeout! 1e3)
                       deref)]
          (is (= 200 (:status resp)))
          (is (= "" (bs/to-string (:body resp)))))))))


(defn try-start-server [options]
  (try
    (with-server (http/start-server identity (merge http-server-options options))
      :started)
    (catch Exception e
      e)))

(deftest test-http-versions-server-config
  (testing "ssl-context as options map"

    (testing "with different HTTP versions in ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2]
                     :ssl-context (assoc test-ssl/server-ssl-context-opts
                                         :application-protocol-config
                                         (netty/application-protocol-config [:http1]))})]
        (is (instance? ExceptionInfo result))
        (is (= "Some desired HTTP versions are not part of ALPN config." (ex-message result)))))

    (testing "with different preference order in ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context (assoc test-ssl/server-ssl-context-opts
                                         :application-protocol-config
                                         (netty/application-protocol-config [:http1 :http2]))})]
        (is (instance? ExceptionInfo result))
        (is (= "Desired HTTP version preference order differs from ALPN config." (ex-message result)))))

    (testing "with extra HTTP versions in the ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http1]
                     :ssl-context (assoc test-ssl/server-ssl-context-opts
                                         :application-protocol-config
                                         (netty/application-protocol-config [:http1 :http2]))})]
        (is (instance? ExceptionInfo result))
        (is (= "ALPN config contains more HTTP versions than desired." (ex-message result)))))

    (testing "with matching ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context (assoc test-ssl/server-ssl-context-opts
                                         :application-protocol-config
                                         (netty/application-protocol-config [:http2 :http1]))})]
        (is (= :started result))))

    (testing "with no ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context test-ssl/server-ssl-context-opts})]
        (is (= :started result)))))

  (testing "ssl-context as SslContext instance"

    (testing "with different HTTP versions in ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2]
                     :ssl-context (netty/ssl-server-context
                                   (assoc test-ssl/server-ssl-context-opts
                                          :application-protocol-config
                                          (netty/application-protocol-config [:http1])))})]
        (is (instance? ExceptionInfo result))
        (is (= "Some desired HTTP versions are not part of ALPN config." (ex-message result)))))

    (testing "with different preference order in ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context (netty/ssl-server-context
                                   (assoc test-ssl/server-ssl-context-opts
                                          :application-protocol-config
                                          (netty/application-protocol-config [:http1 :http2])))})]
        (is (instance? ExceptionInfo result))
        (is (= "Desired HTTP version preference order differs from ALPN config." (ex-message result)))))

    (testing "with extra HTTP versions in the ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http1]
                     :ssl-context (netty/ssl-server-context
                                   (assoc test-ssl/server-ssl-context-opts
                                          :application-protocol-config
                                          (netty/application-protocol-config [:http1 :http2])))})]
        (is (instance? ExceptionInfo result))
        (is (= "ALPN config contains more HTTP versions than desired." (ex-message result)))))

    (testing "with matching ALPN config"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context (netty/ssl-server-context
                                   (assoc test-ssl/server-ssl-context-opts
                                          :application-protocol-config
                                          (netty/application-protocol-config [:http2 :http1])))})]
        (is (= :started result))))

    (testing "with no ALPN config but desiring HTTP/2"
      (let [result (try-start-server
                    {:http-versions [:http2 :http1]
                     :ssl-context test-ssl/server-ssl-context})]
        (is (instance? ExceptionInfo result))
        (is (= "Supplied SslContext with no ALPN config, but requested secure non-HTTP/1 versions that require ALPN." (ex-message result)))))

    (testing "with no ALPN config but desiring only HTTP/1"
      (let [result (try-start-server
                    {:http-versions [:http1]
                     :ssl-context test-ssl/server-ssl-context})]
        (is (= :started result)))))

  (testing "HTTP/2 without ssl-context"
    (let [result (try-start-server
                  {:http-versions [:http2]})]
      (is (instance? IllegalArgumentException result))
      (is (= "HTTP/2 requires ssl-context to be given or use-h2c? to be true." (ex-message result)))))

  (testing "HTTP/2 without ssl-context but with h2c"
    (let [result (try-start-server
                  {:use-h2c? true
                   :http-versions [:http2]})]
      (is (= :started result))))

  (testing "HTTP/2 with ssl-context *and* with h2c"
    (let [result (try-start-server
                  {:use-h2c? true
                   :http-versions [:http2]
                   :ssl-context test-ssl/server-ssl-context-opts})]
      (is (instance? IllegalArgumentException result))
      (is (= "use-h2c? must not be true when ssl-context is given." (ex-message result)))))

  (testing "h2c without HTTP/2"
    (let [result (try-start-server
                  {:use-h2c? true
                   :http-versions [:http1]})]
      (is (instance? IllegalArgumentException result))
      (is (= "use-h2c? may only be true when HTTP/2 is enabled." (ex-message result))))))

(defn ex-root-cause [e]
  (if-let [c (ex-cause e)]
    (recur c)
    e))

(defn try-request-with-pool [options]
  (try
    (let [^Pool pool (http/connection-pool options)]
      (try
        @(http-get "/" {:pool pool})
        :success
        (finally
          (.shutdown pool))))
    (catch Exception e
      ;; Exceptions raised during connection creation are wrapped in multiple levels of
      ;; `RuntimeException` which we don't care about here.
      (ex-root-cause e))))

(deftest test-http-versions-client-config
  (with-http-ssl-servers echo-handler {}
    (testing "ssl-context as options map"

      (testing "with different HTTP versions in ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2]
                        :ssl-context (assoc test-ssl/client-ssl-context-opts
                                            :application-protocol-config
                                            (netty/application-protocol-config [:http1]))}})]
          (is (instance? ExceptionInfo result))
          (is (= "Some desired HTTP versions are not part of ALPN config." (ex-message result)))))

      (testing "with different preference order in ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context (assoc test-ssl/client-ssl-context-opts
                                            :application-protocol-config
                                            (netty/application-protocol-config [:http1 :http2]))}})]
          (is (instance? ExceptionInfo result))
          (is (= "Desired HTTP version preference order differs from ALPN config." (ex-message result)))))

      (testing "with extra HTTP versions in the ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http1]
                        :ssl-context (assoc test-ssl/client-ssl-context-opts
                                            :application-protocol-config
                                            (netty/application-protocol-config [:http1 :http2]))}})]
          (is (instance? ExceptionInfo result))
          (is (= "ALPN config contains more HTTP versions than desired." (ex-message result)))))

      (testing "with matching ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context (assoc test-ssl/client-ssl-context-opts
                                            :application-protocol-config
                                            (netty/application-protocol-config [:http2 :http1]))}})]
          (is (= :success result))))

      (testing "with no ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context test-ssl/client-ssl-context-opts}})]
          (is (= :success result)))))

    (testing "ssl-context as SslContext instance"

      (testing "with different HTTP versions in ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2]
                        :ssl-context (netty/ssl-client-context
                                      (assoc test-ssl/client-ssl-context-opts
                                             :application-protocol-config
                                             (netty/application-protocol-config [:http1])))}})]
          (is (instance? ExceptionInfo result))
          (is (= "Some desired HTTP versions are not part of ALPN config." (ex-message result)))))

      (testing "with different preference order in ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context (netty/ssl-client-context
                                      (assoc test-ssl/client-ssl-context-opts
                                             :application-protocol-config
                                             (netty/application-protocol-config [:http1 :http2])))}})]
          (is (instance? ExceptionInfo result))
          (is (= "Desired HTTP version preference order differs from ALPN config." (ex-message result)))))

      (testing "with extra HTTP versions in the ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http1]
                        :ssl-context (netty/ssl-client-context
                                      (assoc test-ssl/client-ssl-context-opts
                                             :application-protocol-config
                                             (netty/application-protocol-config [:http1 :http2])))}})]
          (is (instance? ExceptionInfo result))
          (is (= "ALPN config contains more HTTP versions than desired." (ex-message result)))))

      (testing "with matching ALPN config"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context (netty/ssl-client-context
                                      (assoc test-ssl/client-ssl-context-opts
                                             :application-protocol-config
                                             (netty/application-protocol-config [:http2 :http1])))}})]
          (is (= :success result))))

      (testing "with no ALPN config but desiring HTTP/2"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2 :http1]
                        :ssl-context test-ssl/client-ssl-context}})]
          (is (instance? ExceptionInfo result))
          (is (= "Supplied SslContext with no ALPN config, but requested secure non-HTTP/1 versions that require ALPN." (ex-message result)))))

      (testing "with no ALPN config but desiring only HTTP/1"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http1]
                        :ssl-context test-ssl/client-ssl-context}})]
          (is (= :success result)))))

    (testing "HTTP/2 without ssl-context"
      (let [result (try-request-with-pool
                    {:connection-options
                     {:http-versions [:http2]
                      ;; We only want assert that not providing `:ssl-context` is possible but since
                      ;; the test server uses a self-signed certificate, we cannot verify it with
                      ;; the default one. Since all we care about is ALPN here, this is fine.
                      :insecure? true}})]
        (is (= :success result)))))

  (with-http2-server echo-handler {:use-h2c? true :ssl-context nil}
    (with-redefs [*use-tls-requests* false]
      (testing "h2c with HTTP/2"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:force-h2c? true
                        :http-versions [:http2]}})]
          (is (= :success result))))

      (testing "h2c without HTTP/2"
        (let [result (try-request-with-pool
                      {:connection-options
                       {:force-h2c? true
                        :http-versions [:http1]}})]
          (is (instance? IllegalArgumentException result))
          (is (= "force-h2c? may only be true when HTTP/2 is enabled." (ex-message result))))))))

(deftest http2-only-client-connecting-to-http1-only-server
  (testing "No ALPN config, desiring only HTTP/2 but the server only allows HTTP/1"
    (with-http1-server echo-handler http1-ssl-server-options
      (with-redefs [*use-tls-requests* true]
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http2]
                        :ssl-context test-ssl/client-ssl-context-opts}})]
          (is (= :success result) "succeeds due to the default failure behaviors (see docstring of `application-protocol-config`)"))))))


(deftest http1-only-client-connecting-to-http2-only-server
  (testing "No ALPN config, desiring only HTTP/1.1 but the server only allows HTTP/2"
    (with-http2-server echo-handler {}
      (with-redefs [*use-tls-requests* true]
        (let [result (try-request-with-pool
                      {:connection-options
                       {:http-versions [:http1]
                        :ssl-context test-ssl/client-ssl-context-opts}})]
          (is (= :success result) "succeeds due to the default failure behaviors (see docstring of `application-protocol-config`)"))))))


(deftest test-in-flight-request-cancellation
  (let [conn-established (promise)
        conn-closed (promise)]
    (with-raw-handler (fn [req]
                        (deliver conn-established true)
                        (s/on-closed (:body req)
                                     (fn []
                                       (deliver conn-closed true))))
      (let [rsp (http-get "/")]
        (is (= true (deref conn-established 1000 :timeout)))
        (http/cancel-request! rsp)
        (is (= true (deref conn-closed 1000 :timeout)))
        (is (thrown? RequestCancellationException (deref rsp 1000 :timeout)))))))

(deftest test-client-proxy-support
  (with-http-ssl-servers basic-handler {}
    (let [proxy-request (d/deferred)
          proxy-server (tcp/start-server
                        (fn [stream _]
                          (d/future
                            (try
                              (let [stream-ch (:aleph/channel (meta stream))
                                    req (http.core/netty-request->ring-request
                                         @(d/timeout! (s/take! stream) 1000)
                                         false
                                         stream-ch
                                         nil)]
                                (assert (instance? LastHttpContent @(d/timeout! (s/take! stream) 1000)))
                                (-> stream-ch .pipeline (.remove "http-request-decoder"))
                                (when (= :connect (:request-method req))
                                  (when-let [[host port] (some-> req :uri (str/split #":" 2))]
                                    (when-let [client (-> (tcp/client {:host host
                                                                       :port (parse-long port)
                                                                       :connect-timeout 1000})
                                                          (d/timeout! 2000)
                                                          deref)]
                                      (s/put! stream (netty/to-byte-buf "HTTP/1.0 200 Connection established\r\n\r\n"))
                                      (s/connect stream client)
                                      (s/connect client stream))))
                                (d/success! proxy-request req))
                              (catch Throwable e
                                (d/error! proxy-request e)))))
                        {:port 0
                         :shutdown-timeout 0
                         :raw-stream? true
                         :pipeline-transform (fn [^ChannelPipeline p]
                                               (.addFirst p "http-request-decoder" (HttpRequestDecoder.)))})]
      (binding [*connection-options* {:proxy-options {:host "localhost"
                                                      :port (netty/port proxy-server)}}]
        (with-server proxy-server
          (is (= string-response (some-> (http-get "/string")
                                         (d/timeout! 1000)
                                         deref
                                         :body
                                         bs/to-string)))
          (is (= {:request-method :connect
                  :uri (str "localhost:" port)
                  :headers {"host" (str "localhost:" port)
                            "proxy-connection" "Keep-Alive"}
                  :body nil}
                 (select-keys @(d/timeout! proxy-request 1000)
                              [:request-method :uri :headers :body]))))))))

(deftest test-client-proxy-connection-timeout
  (with-http-ssl-servers basic-handler {}
    (let [proxy-server (tcp/start-server (fn [_ _]
                                           ;; Accept connection and let it linger so that the
                                           ;; client's proxy connection timeout fires.
                                           )
                                         {:port 0
                                          :shutdown-timeout 0})]
      (binding [*connection-options* {:proxy-options {:host "localhost"
                                                      :port (netty/port proxy-server)
                                                      :connection-timeout 10}}]
        (with-server proxy-server
          (try
            @(d/timeout! (http-get "/string") 1000)
            (is (= true false) "Should have thrown an exception")
            (catch Exception e
              (is (instance? ProxyConnectionTimeoutException e)))))))))

(deftest test-client-proxy-connect-error
  (with-http-ssl-servers basic-handler {}
    (let [proxy-server (tcp/start-server (fn [stream _]
                                           ;; Immediately close connection so that we get a
                                           ;; non-timeout proxy connection error on the client-side.
                                           (s/close! stream))
                                         {:port 0
                                          :shutdown-timeout 0})]
      (binding [*connection-options* {:proxy-options {:host "localhost"
                                                      :port (netty/port proxy-server)}}]
        (with-server proxy-server
          (try
            @(d/timeout! (http-get "/string") 1000)
            (is (= true false) "Should have thrown an exception")
            (catch Exception e
              (is (instance? ProxyConnectException e)))))))))

(deftest ^:leak test-leak-in-raw-stream-handler
  ;; NOTE: Expecting 2 leaks because `with-raw-handler` will run its body for both http1 and
  ;; http2. It would be nicer to put this assertion into the body but the http1 server seems to
  ;; only really leak the buffer after shutting down.
  (aleph.resource-leak-detector/with-expected-leaks 2
    (with-raw-handler basic-handler
      (let [resp @(http-put "/string"
                            ;; NOTE: The request handler doesn't consume this body.
                            ;; As per the :raw-stream?  contract, this leads to a buffer leak.
                            {:body "Hello, world!"})]
        (is (= 200 (:status resp)))
        (is (= "String!" (bs/to-string (:body resp))))))))

(aleph.resource-leak-detector/instrument-tests!)
