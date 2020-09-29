(ns aleph.http-test
  (:use
    [clojure test])
  (:require
    [clojure.java.io :as io]
    [aleph
     [http :as http]
     [netty :as netty]
     [flow :as flow]
     [tcp :as tcp]]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [clojure.string :as str])
  (:import
    [java.util.concurrent
     Executors
     SynchronousQueue
     TimeoutException
     TimeUnit
     ThreadPoolExecutor
     ThreadPoolExecutor$AbortPolicy]
    [java.io
     File
     ByteArrayInputStream]
    [java.net UnknownHostException]
    [java.util.zip
     GZIPInputStream
     ZipException]
    [aleph.utils
     ConnectionTimeoutException
     RequestTimeoutException]
    [io.netty.handler.codec
     TooLongFrameException]
    [io.netty.channel
     ChannelHandler
     ChannelHandlerContext
     ChannelPipeline]))

;;;

(set! *warn-on-reflection* false)

(def ^:dynamic ^io.aleph.dirigiste.IPool *pool* nil)
(def ^:dynamic *connection-options* nil)
(def ^:dynamic *response* nil)

(netty/leak-detector-level! :paranoid)

(defn default-options []
  {:socket-timeout 1e3
   :pool-timeout 1e4
   :request-timeout 1e4
   :throw-exceptions? false})

(defn http-get
  ([url]
    (http-get url nil))
  ([url options]
    (http/get url (merge (default-options) {:pool *pool*} options))))

(defn http-post
  ([url]
   (http-post url nil))
  ([url options]
   (http/post url (merge (default-options) {:pool *pool*} options))))

(defn http-put
  ([url]
    (http-put url nil))
  ([url options]
    (http/put url (merge (default-options) {:pool *pool*} options))))

(def port 8082)

(def filepath (str (System/getProperty "user.dir") "/test/file.txt"))

(def string-response "String!")
(def seq-response (map identity ["sequence: " 1 " two " 3.0]))
(def file-response (File. filepath))
(def http-file-response (http/file filepath))
(def http-file-region-response (http/file filepath 5 4))
(def stream-response "Stream!")

(defn string-handler [request]
  {:status 200
   :body string-response})

(defn seq-handler [request]
  {:status 200
   :body seq-response})

(defn file-handler [request]
  {:status 200
   :body file-response})

(defn http-file-handler [request]
  {:status 200
   :body http-file-response})

(defn http-file-region-handler [request]
  {:status 200
   :body http-file-region-response})

(defn stream-handler [request]
  {:status 200
   :body (bs/to-input-stream stream-response)})

(defn slow-handler [request]
  {:status 200
   :body (cons "1" (lazy-seq (do (Thread/sleep 500) '("2"))))})

(defn manifold-handler [request]
  {:status 200
   :body (->> stream-response (map str) s/->source)})

(defn echo-handler [request]
  {:status 200
   :body (:body request)})

(defn line-echo-handler [request]
  {:status 200
   :body (->> request :body bs/to-line-seq)})

(defn hello-handler [request]
  {:status 200
   :body "hello"})

(defn big-handler [request]
  {:status 200
   :body (->> (s/periodically 0.1 #(byte-array 1024))
           (s/transform (take 1e3)))})

(defn redirect-handler [{:keys [query-string] :as request}]
  (let [count (-> (.split #"[?=]" query-string) second Integer/parseInt)
        host (-> request :headers (get "host"))]
    (if (zero? count)
      {:status 200 :body "ok"}
      {:status 302
       :headers {"location" (str "http://"
                              (if (= "localhost" host)
                                "127.0.0.1"
                                "localhost")
                              ":" port
                              "/redirect?count=" (dec count))}
       :body "redirected!"})))

(def latch (promise))
(def browser-server (atom nil))

(def route-map
  {"/big" big-handler
   "/redirect" redirect-handler
   "/stream" stream-handler
   "/slow" slow-handler
   "/file" file-handler
   "/httpfile" http-file-handler
   "/httpfileregion" http-file-region-handler
   "/manifold" manifold-handler
   "/seq" seq-handler
   "/string" string-handler
   "/echo" echo-handler
   "/line_echo" line-echo-handler
   "/stop" (fn [_]
             (try
               (deliver latch true) ;;this can be triggered more than once, sometimes
               (.close ^java.io.Closeable @browser-server)
               (catch Exception e)))})

(defn print-vals [& args]
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

(defmacro with-server [server & body]
  `(let [server# ~server]
     (binding [*pool* (http/connection-pool
                       {:connection-options
                        (merge *connection-options*
                               {:insecure? true})})]
       (try
         ~@body
         (finally
           (.close ^java.io.Closeable server#)
           (netty/wait-for-close server#)
           (.shutdown *pool*))))))

(defmacro with-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port})
     ~@body))

(defmacro with-compressed-handler [handler & body]
  `(do
     (with-server (http/start-server ~handler {:port port :compression? true})
       ~@body)
     (with-server (http/start-server ~handler {:port port :compression-level 3})
       ~@body)))

(defmacro with-ssl-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port, :ssl-context (netty/self-signed-ssl-context)})
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port, :raw-stream? true})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

(defmacro with-native-transport [handler & body]
  `(binding [*connection-options* {:epoll? true
                                   :kqueue? true}]
     (with-server (http/start-server ~handler {:port port
                                               :epoll? true
                                               :kqueue? true})
       ~@body)))

;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[index [path result]] (map-indexed vector expected-results)]
      (is
       (= result
          (bs/to-string
           (:body
            @(http-get (str "http://localhost:" port "/" path)))))))))

(when (netty/native-transport-available?)
  (deftest test-response-formats-with-native-transports
    (with-native-transport basic-handler
      (doseq [[index [path result]] (map-indexed vector expected-results)]
        (is
         (= result
            (bs/to-string
             (:body
              @(http-get (str "http://localhost:" port "/" path))))))))))

(deftest test-compressed-response
  (with-compressed-handler basic-handler
    (doseq [[index [path result]] (map-indexed vector expected-results)
            :let [resp @(http-get (str "http://localhost:" port "/" path)
                                  {:headers {:accept-encoding "gzip"}})
                  unzipped (try
                             (bs/to-string (GZIPInputStream. (:body resp)))
                             (catch ZipException _ nil))]]
      (is (= "gzip" (get-in resp [:headers :content-encoding]))
          'content-encoding-header-is-set)
      (is (some? unzipped) 'should-be-valid-gzip)
      (is (= result unzipped) 'decompressed-body-is-correct))))

(defn- client-decompression [manual-header expected-encoding]
  (let [decompress-pool (http/connection-pool
                         {:connection-options {:decompress-body? true
                                               :save-content-encoding? true}})]
    (with-compressed-handler basic-handler
      (doseq [[index [path result]] (map-indexed vector expected-results)
              :let [resp @(http/get
                           (str "http://localhost:" port "/" path)
                           (cond-> {:pool decompress-pool}
                             (some? manual-header)
                             (assoc :headers {:accept-encoding manual-header})))
                    body (bs/to-string (:body resp))]]
        (is (= expected-encoding
               (get-in resp [:headers :x-origin-content-encoding]))
            'content-encoding-header-is-set)
        (is (= result body) 'auto-decompressed-body-is-correct)))))

(deftest test-client-decompress-response
  (testing "default configuration"
    (client-decompression nil "gzip"))

  (testing "gzip priority"
    (client-decompression "deflate, gzip" "gzip"))

  (testing "manually set to gzip"
    (client-decompression "gzip" "gzip"))

  (testing "manually set to deflate"
    (client-decompression "deflate" "deflate")))

(deftest test-ssl-response-formats
  (with-ssl-handler basic-handler
    (doseq [[index [path result]] (map-indexed vector expected-results)]
      (is
       (= result
          (bs/to-string
           (:body
            @(http-get (str "https://localhost:" port "/" path)))))
       (str path "path failed")))))

(def words (slurp "/usr/share/dict/words"))

(deftest test-bulk-requests
  (with-handler basic-handler
    (->> (range 1e2)
      (map (fn [_] (http-get (str "http://localhost:" port "/string"))))
      (apply d/zip)
      deref)
    (dotimes [_ 10]
      (->> (range 10)
        (map (fn [_] (http-get (str "http://localhost:" port "/string"))))
        (apply d/zip)
        deref))))

(deftest test-overly-long-request
  (with-handler basic-handler
    (= 414 @(http-get (apply str "http://localhost:" port  "/" (repeat 1e4 "a"))))))

(deftest test-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5 1e6 1e7]]
      (let [words (->> words (take len) (apply str))
            body (:body
                   @(http-put (str "http://localhost:" port "/echo")
                      {:body words}))
            body' (bs/to-string body)]
        (assert (== (min (count words) len) (count body')))
        (is (= words body'))))))

(deftest test-redirect
  (with-both-handlers basic-handler
    (is (= "ok"
          (-> @(http-get (str "http://localhost:" port "/redirect?count=10"))
            :body
            bs/to-string)))
    (is (= "redirected!"
          (-> @(http-get (str "http://localhost:" port "/redirect?count=25"))
            :body
            bs/to-string)))
    (is (= "ok"
          (-> @(http-get (str "http://localhost:" port "/redirect?count=25")
                 {:max-redirects 30})
            :body
            bs/to-string)))))

(deftest test-middleware
  (with-both-handlers basic-handler
    (is (= "String!"
          (-> @(http-get (str "http://localhost:" port "/stream")
                 {:middleware
                  (fn [client]
                    (fn [req]
                      (client (assoc req :url (str "http://localhost:" port "/string")))))})
            :body
            bs/to-string)))))

(deftest test-line-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5]]
      (let [words (->> words (take len) (apply str))
            body (:body
                   @(http-put (str "http://localhost:" port "/line_echo")
                      {:body words}))]
        (is (= (.replace ^String words "\n" "") (bs/to-string body)))))))

(deftest test-illegal-character-in-url
  (with-handler hello-handler
    (is (= "hello"
          (-> @(http-get (str "http://localhost:" port "/?param=illegal character"))
            :body
            bs/to-string)))))

(deftest test-connection-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http-get "http://192.0.2.0" ;; "TEST-NET" in RFC 5737
             {:connection-timeout 2}))))

  (with-handler basic-handler
    (is (thrown? ConnectionTimeoutException
          @(http-get "http://192.0.2.0" ;; "TEST-NET" in RFC 5737
             {:connection-timeout 2})))))

(deftest test-request-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http-get (str "http://localhost:" port "/slow")
             {:request-timeout 5}))))

  (with-handler basic-handler
    (is (thrown? RequestTimeoutException
          @(http-get (str "http://localhost:" port "/slow")
             {:request-timeout 5})))))

(deftest test-explicit-url
  (with-handler hello-handler
    (is (= "hello" (-> @(http/request {:method :get
                                       :scheme :http
                                       :server-name "localhost"
                                       :server-port port
                                       :request-timeout 1e3})
                     :body
                     bs/to-string)))))

(deftest test-debug-middleware
  (with-handler hello-handler
    (let [url (str "http://localhost:" port)
          r1 @(http/get url {:query-params {:name "John"}
                             :save-request? true
                             :debug-body? true})
          r2 @(http/get url {:save-request? true
                             :debug-body? false})]
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
         (let [rsp (http/get (str "http://localhost:" port) {:connection-pool pool})]
           (is (= http/default-response-executor (.executor rsp))))))))

(defn echo-handler [req]
  {:status 200
   :body (:body req)})

(deftest test-trace-request-omitted-body
  (with-handler echo-handler
    (is (= "" (-> @(http/trace (str "http://localhost:" port) {:body "REQUEST"})
                  :body
                  bs/to-string)))))

(defn non-existing-file-handler [req]
  {:status 200
   :body (File. "this-file-does-not-exist.sure")})

(defn- assert-file-error []
  (is (= 500 (-> (http/get (str "http://localhost:" port)
                           {:throw-exceptions? false})
                 (d/timeout! 1e3)
                 deref
                 :status))))

(deftest test-sending-non-existing-file
  (testing "sending FileRegion"
    (with-handler non-existing-file-handler
      (assert-file-error)))

  (testing "sending chunked body"
    (with-compressed-handler non-existing-file-handler
      (assert-file-error))))

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
            @(http/get (str "http://localhost:" port "/string"))))))
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

;;;

(deftest test-large-responses
  (with-handler basic-handler
    (let [pool (http/connection-pool {:connection-options {:response-buffer-size 16}})]
      (dotimes [i 1 #_1e6]
        #_(when (zero? (rem i 1e2))
            (prn i))
        (-> @(http/get (str "http://localhost:" port "/big")
               {:as :byte-array})
          :body
          count)))))

;;;
;;; errors processing
;;;

(defn echo-string-handler [{:keys [body]}]
  {:status 200
   :body (bs/to-string body)})

(def http-line-delimiter "\r\n")

(def http-message-delimiter (str http-line-delimiter http-line-delimiter))

(defn encode-http-object [lines]
  (str (str/join http-line-delimiter lines) http-message-delimiter))

(defn tcp-handler [response]
  (fn [s ch]
    (s/take! s)
    (s/put! s (encode-http-object response))))

(defmacro with-tcp-response [response & body]
  `(with-server (tcp/start-server (tcp-handler ~response) {:port port})
     ~@body))

(defmacro with-tcp-request [options request & body]
  `(with-tcp-request-handler echo-handler ~options ~request ~@body))

(defmacro with-tcp-request-handler [handler options request & body]
  `(with-server (http/start-server ~handler (assoc ~options :port port))
     (let [conn# @(tcp/client {:host "localhost" :port port})
           decode# (fnil bs/to-string "")]
       (s/put! conn# (encode-http-object ~request))
       (binding [*response* (-> conn# s/take! deref decode#)]
         ~@body)
       (s/close! conn#))))

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
                 (-> (http-post (str "http://localhost:" port) {:body "hello!"})
                     (d/timeout! 1e3)
                     deref)))))

(deftest test-client-errors-handling
  (testing "writing invalid request message"
    (with-handler echo-string-handler
      (is (thrown? IllegalArgumentException
                   (-> (http-post (str "http://localhost:" port) {:body 42})
                       (d/timeout! 1e3)
                       deref)))))

  (testing "writing invalid request body"
    (with-handler echo-string-handler
      (is (thrown? IllegalArgumentException
                   (-> (http-post (str "http://localhost:" port) {:body (s/->source [1])})
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
                     (-> (http-post (str "http://localhost:" port) {:body "hello!"})
                         (d/timeout! 1e3)
                         deref))))))

  (testing "reading invalid chunked response body"
    (with-tcp-response ["HTTP/1.1 4000001 Super Bad Request"
                        "Server: Aleph"
                        "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                        "Connection: Keep-Alive"
                        "transfer-encoding: chunked"
                        ""
                        "not-a-number" ;; definitely not parseable chunk size
                        "fail"
                        "0"]
      ;; xxx(okachaiev): do we need to wrap this into more generic `DecoderException`?
      (let [rsp (-> (http-post (str "http://localhost:" port))
                    (d/timeout! 1e3)
                    deref)]
        (bs/to-string (:body rsp))
        (is (d/deferred? (:aleph/interrupted? rsp)))
        (is (true? @(:aleph/interrupted? rsp))))))

  (testing "response body larger then content-length"
    (binding [*connection-options* {:response-buffer-size 1
                                    :keep-alive? true}]
      (let [long-line "hello body is too long to be true"]
        (with-tcp-response ["HTTP/1.1 200 OK"
                            "Server: Aleph"
                            "Date: Tue, 29 Sep 2020 08:01:42 GMT"
                            "Content-Length: 1"
                            "Connection: Keep-Alive"
                            ""
                            long-line]
          (is (= "h" (-> (http-get (str "http://localhost:" port))
                         (d/timeout! 1e3)
                         deref
                         :body
                         bs/to-string)))
          (is (= "h" (-> (http-get (str "http://localhost:" port))
                         (d/timeout! 1e3)
                         deref
                         :body
                         bs/to-string)))))))

  (testing "unknown host with JDK DNS resolver"
    (is (thrown? UnknownHostException
                 (-> (http/get "http://unknown-host/")
                     (d/timeout! 1e3)
                     deref))))

  (testing "unknown host with custom DNS client"
    (let [pool (http/connection-pool
                {:connection-options (assoc (default-options) :insecure? true)
                 :dns-options {:name-servers ["1.1.1.1"]}})]
      (try
        (is (thrown? UnknownHostException
                     (-> (http/get "http://unknown-host/" {:pool pool})
                         (d/timeout! 1e3)
                         deref)))
        (finally
          (.shutdown pool))))))

(defmacro with-rejected-handler [handler & body]
  `(let [handler# ~handler
         ^ThreadPoolExecutor executor# (ThreadPoolExecutor.
                                        1 1 0 TimeUnit/MILLISECONDS (SynchronousQueue.)
                                        (ThreadPoolExecutor$AbortPolicy.))
         d# (d/deferred)]
     (try
       (.execute executor# (fn [] @d#))
       (with-server (http/start-server echo-handler {:port port
                                                     :executor executor#
                                                     :shutdown-executor? false
                                                     :rejected-handler handler#})
         ~@body)
       (finally
         (d/success! d# true)
         (.shutdown executor#)))))

(deftest test-server-errors-handling
  (testing "rejected handler when accepting connection"
    (with-rejected-handler nil
      (is (= 503 (-> (http-get (str "http://localhost:" port))
                     (d/timeout! 1e3)
                     deref
                     :status)))))

  (testing "custom rejected handler"
    (with-rejected-handler (fn [_] {:status 201 :body "I'm actually okayish"})
      (is (= 201 (-> (http-get (str "http://localhost:" port))
                     (d/timeout! 1e3)
                     deref
                     :status)))))

  (testing "throwing exception within rejected handler"
    (with-rejected-handler (fn [_] (throw (RuntimeException. "you shall not reject!")))
      (let [resp (-> (http-get (str "http://localhost:" port))
                     (d/timeout! 1e3)
                     deref)]
        (is (= 500 (:status resp)))
        (is (= "Internal Server Error" (bs/to-string (:body resp)))))))

  (testing "throwing exception within ring handler"
    (with-handler (fn [_] (throw (RuntimeException. "you shall not pass!")))
      (let [resp (-> (http-get (str "http://localhost:" port))
                     (d/timeout! 1e3)
                     deref)]
        (is (= 500 (:status resp)))
        (is (= "Internal Server Error" (bs/to-string (:body resp)))))))

  (testing "reading invalid request message: bad request"
    ;; this request should fail with `IllegalArgumentException` "multiple Content-Lenght headers"
    (with-tcp-request {} ["GET / HTTP/1.1"
                          "content-length: 0"
                          "content-length: 1"]
      (is (.startsWith *response* "HTTP/1.1 400 Bad Request"))))

  (testing "reading invalid request message: URI is too long"
    (with-tcp-request
      {:max-initial-line-length 1}
      ["GET /not-very-long-uri-though HTTP/1.1"
       "content-length: 0"]
      (is (.startsWith *response* "HTTP/1.1 414 Request-URI Too Long"))))

  (testing "reading invalid request message: header is too large"
    (with-tcp-request
      {:max-header-size 5}
      ["GET / HTTP/1.1"
       "content-length: 0"
       "header: value-that-is-definitely-too-large"]
      (is (.startsWith *response* "HTTP/1.1 431 Request Header Fields"))))

  (testing "reading invalid request body"
    (let [interrupted? (d/deferred)]
      (with-tcp-request-handler
        (fn [req]
          (try
            (bs/to-string (:body req))
            (catch Throwable _))
          (d/success! interrupted? @(:aleph/interrupted? req))
          {:status 201
           :body "not good"})
        {:request-buffer-size 1}
        ["POST / HTTP/1.1"
         "transfer-encoding: chunked"
         ""
         "not-a-number" ;; definitely not parseable chunk size
         "fail"
         "0"]
        (is (true? @interrupted?)))))

  (testing "writing invalid response message"
    (let [invalid-status-handler
          (fn [{:keys [body]}]
            (when (some? body) (bs/to-string body))
            {:status 1045
             ;; this leads to `NullPointerException` in `http.core/normalize-header-key`
             :headers {nil "not such header"}
             :body "there's no such status"})]
      (with-handler invalid-status-handler
        (is (= 500 (-> (http-get (str "http://localhost:" port))
                       (d/timeout! 1e3)
                       deref
                       :status))))))

  (testing "writing invalid response body"
    (let [invalid-body-handler
          (fn [{:keys [body]}]
            (when (some? body) (bs/to-string body))
            {:status 200
             :body (s/->source [1])})]
      (with-handler invalid-body-handler
        (let [resp (-> (http-get (str "http://localhost:" port))
                       (d/timeout! 1e3)
                       deref)]
          (is (= 200 (:status resp)))
          (is (true? @(:aleph/interrupted? resp))))))))

(deftest test-custom-error-handler
  (let [error (atom "")
        logger (fn [^Throwable ex]
                 (reset! error (.getMessage ex)))
        fire-exception (netty/channel-inbound-handler
                        :channel-active
                        ([_ ctx]
                         (let [ex (RuntimeException. "you shall not log!")]
                           (.fireExceptionCaught ^ChannelHandlerContext ctx ex))))
        pipeline (fn [^ChannelPipeline p]
                   (.addBefore p "request-handler" "fire-exception" ^ChannelHandler fire-exception))]
    (with-server (http/start-server echo-handler {:port port
                                                  :error-logger logger
                                                  :pipeline-transform pipeline})
      (-> (http-get (str "http://localhost:" port))
          (d/timeout! 1e3)
          (d/catch' (fn [_]))
          deref))
    (is (.contains @error "not log!"))))

;;;

(deftest ^:benchmark benchmark-http
  (println "starting HTTP benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server hello-handler {:port 8080})]
    (Thread/sleep (* 1000 60))
    (println "stopping server")
    (.close ^java.io.Closeable server)))

(deftest ^:benchmark benchmark-websockets
  (println "starting WebSocket benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server
                 (fn [req]
                   (d/let-flow [s (http/websocket-connection req)]
                     (s/consume #(s/put! s %) s)))
                 {:port 8080})]
    (Thread/sleep (* 1000 60))
    (println "stopping server")
    (.close ^java.io.Closeable server)))
