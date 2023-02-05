(ns aleph.http-test
  (:require
   [aleph.flow :as flow]
   [aleph.http :as http]
   [aleph.http.core :as http.core]
   [aleph.netty :as netty]
   [aleph.ssl :as ssl]
   [aleph.tcp :as tcp]
   [clj-commons.byte-streams :as bs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as d]
   [manifold.stream :as s]
   [manifold.time :as t])
  (:import
   (aleph.utils ConnectionTimeoutException RequestTimeoutException)
   (io.aleph.dirigiste Pool)
   (io.netty.channel ChannelHandlerContext ChannelOutboundHandlerAdapter ChannelPipeline ChannelPromise)
   (io.netty.handler.codec TooLongFrameException)
   (io.netty.handler.codec.http HttpMessage HttpObjectAggregator)
   (java.io File)
   (java.net UnknownHostException)
   (java.util.concurrent SynchronousQueue TimeoutException ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit)
   (java.util.zip GZIPInputStream ZipException)
   (javax.net.ssl SSLSession)))

;;;

(set! *warn-on-reflection* false)

(def ^:dynamic ^io.aleph.dirigiste.IPool *pool* nil)
(def ^:dynamic *connection-options* nil)
(def ^:dynamic ^String *response* nil)

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

(defn string-handler [_request]
  {:status 200
   :body string-response})

(defn seq-handler [_request]
  {:status 200
   :body seq-response})

(defn file-handler [_request]
  {:status 200
   :body file-response})

(defn http-file-handler [_request]
  {:status 200
   :body http-file-response})

(defn http-file-region-handler [_request]
  {:status 200
   :body http-file-region-response})

(defn stream-handler [_request]
  {:status 200
   :body (bs/to-input-stream stream-response)})

(defn slow-handler [_request]
  {:status 200
   :body (cons "1" (lazy-seq (do (Thread/sleep 500) '("2"))))})

(defn manifold-handler [_request]
  {:status 200
   :body (->> stream-response (map str) s/->source)})

(defn echo-handler [request]
  {:status 200
   :body (:body request)})

(defn line-echo-handler [request]
  {:status 200
   :body (->> request :body bs/to-line-seq)})

(defn hello-handler [_request]
  {:status 200
   :body "hello"})

(defn invalid-handler [_]
  {:status -100
   :body "hello"})

(defn big-handler [_]
  {:status 200
   :body (->> (s/periodically 0.1 #(byte-array 1024))
              (s/transform (take (long 1e3))))})

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

(defn text-plain-handler [_]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "Hello"})

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
   "/invalid" invalid-handler
   "/text_plain" text-plain-handler
   "/stop" (fn [_]
             (try
               (deliver latch true) ;;this can be triggered more than once, sometimes
               (.close ^java.io.Closeable @browser-server)
               (catch Exception _)))})

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
     (binding [*pool* (http/connection-pool {:connection-options (merge *connection-options* {:insecure? true})})]
       (try
         ~@body
         (finally
           (.close ^java.io.Closeable server#)
           (netty/wait-for-close server#)
           (.shutdown *pool*))))))

(defmacro with-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port :shutdown-timeout 0})
     ~@body))

(defmacro with-compressed-handler [handler & body]
  `(do
     (with-server (http/start-server ~handler {:port port :compression? true :shutdown-timeout 0})
       ~@body)
     (with-server (http/start-server ~handler {:port port :compression-level 3 :shutdown-timeout 0})
       ~@body)))

(def default-ssl-options {:port port, :ssl-context (netty/self-signed-ssl-context)})

(defmacro with-handler-options
  [handler options & body]
  `(with-server (http/start-server ~handler (assoc ~options :shutdown-timeout 0))
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port, :raw-stream? true :shutdown-timeout 0})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[path result] expected-results]
      (is (= result
             (bs/to-string
              (:body
               @(http-get (str "http://localhost:" port "/" path)))))))))

(deftest test-compressed-response
  (with-compressed-handler basic-handler
    (doseq [[path result] expected-results
            :let [resp @(http-get (str "http://localhost:" port "/" path)
                                  {:headers {:accept-encoding "gzip"}})
                  unzipped (try
                             (bs/to-string (GZIPInputStream. (:body resp)))
                             (catch ZipException _ nil))]]
      (is (= "gzip" (get-in resp [:headers :content-encoding])) 'content-encoding-header-is-set)
      (is (some? unzipped) 'should-be-valid-gzip)
      (is (= result unzipped) 'decompressed-body-is-correct))))


(deftest test-ssl-response-formats
  (with-handler-options basic-handler default-ssl-options
    (doseq [[path result] expected-results]
      (is
       (= result
          (bs/to-string
           (:body
            @(http-get (str "https://localhost:" port "/" path)))))
       (str path "path failed")))))

(deftest test-files
  (let [client-url (str "http://localhost:" port)]
    (with-handler identity
      (is (str/blank?
             (bs/to-string
              (:body @(http-put client-url
                                {:body (io/file "test/empty.txt")})))))
      (is (= (slurp "test/file.txt" :encoding "UTF-8")
             (bs/to-string
              (:body @(http-put client-url
                                {:body (io/file "test/file.txt")}))))))))

(deftest test-ssl-files
  (let [client-url (str "https://localhost:" port)
        client-options {:connection-options {:ssl-context ssl/client-ssl-context}}
        client-pool    (http/connection-pool client-options)]
    (with-handler-options identity (merge default-ssl-options {:ssl-context ssl/server-ssl-context})
      (is (str/blank?
           (bs/to-string
            (:body @(http-put client-url
                              {:body (io/file "test/empty.txt")
                               :pool client-pool})))))
      (is (= (slurp "test/file.txt" :encoding "UTF-8")
             (bs/to-string
              (:body @(http-put client-url
                                {:body (io/file "test/file.txt")
                                 :pool client-pool}))))))))

(defn ssl-session-capture-handler [ssl-session-atom]
  (fn [req]
    (reset! ssl-session-atom (http.core/ring-request-ssl-session req))
    {:status 200 :body "ok"}))

(deftest test-ssl-session-access
  (let [ssl-session (atom nil)]
    (with-handler-options
      (ssl-session-capture-handler ssl-session)
      default-ssl-options
      (is (= 200 (:status @(http-get (str "https://localhost:" port)))))
      (is (some? @ssl-session))
      (when-let [^SSLSession s @ssl-session]
        (is (.isValid s))
        (is (not (str/includes? "NULL" (.getCipherSuite s))))))))

(deftest test-ssl-with-plain-client-request
  (let [ssl-session (atom nil)]
    (with-handler-options
      (ssl-session-capture-handler ssl-session)
      default-ssl-options
      ;; Note the intentionally wrong "http" scheme here
      (is (some-> (http-get (str "http://localhost:" port))
                  (d/catch identity)
                  deref
                  ex-message
                  (str/includes? "connection was closed")))
      (is (nil? @ssl-session)))))

(deftest test-invalid-body
  (let [client-url (str "http://localhost:" port)]
    (with-handler identity
      (is (thrown? IllegalArgumentException
                   @(http-put client-url
                              {:body 123}))))))

(def characters
  (let [charset (conj (mapv char (range 32 127)) \newline)]
    (repeatedly #(rand-nth charset))))

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

(deftest test-overly-long-url
  (let [long-url (apply str "http://localhost:" port  "/" (repeat (long 1e4) "a"))]
    (with-handler basic-handler
      (is (= 414 (:status @(http-get long-url)))))))

(deftest test-overly-long-header
  (let [url (str "http://localhost:" port)
        long-header-value (apply str (repeat (long 1e5) "a"))
        opts {:headers {"X-Long" long-header-value}}]
    (with-handler basic-handler
      (is (= 431 (:status @(http-get url opts)))))))

(deftest test-invalid-http-version-format
  (with-handler basic-handler
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
                   @(http-put (str "http://localhost:" port "/echo")
                      {:body characters}))
            body' (bs/to-string body)]
        (assert (== (min (count characters) len) (count body')))
        (is (= characters body'))))))

(deftest test-redirect
  (testing "basic redirecting"
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

  (testing "works with :method as well as :request-method"
    (with-handler
      (fn [{:keys [uri]}]
        (case uri
          "/200" {:status 200}
          "/301" {:status  301
                  :headers {"Location" "/200"}}))
      (is (= 200 (:status @(http/request {:method            :get
                                          :url               (str "http://localhost:" port "/301")
                                          :follow-redirects? true})))))))

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
      (let [characters (->> characters (take len) (apply str))
            body (:body
                   @(http-put (str "http://localhost:" port "/line_echo")
                      {:body characters}))]
        (is (= (.replace ^String characters "\n" "") (bs/to-string body)))))))

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

(deftest test-trace-request-omitted-body
  (with-handler echo-handler
    (is (= "" (-> @(http/trace (str "http://localhost:" port) {:body "REQUEST"})
                  :body
                  bs/to-string)))))

(deftest test-invalid-response
  (with-handler invalid-handler
    (let [{:keys [body status]} @(http-get (apply str "http://localhost:" port  "/invalid"))]
      (is (= 500 status))
      (is (re-find #"Internal Server Error"
                   (bs/to-string body)))))
  (testing "custom error handler"
    (with-server (http/start-server invalid-handler
                                    {:port port
                                     :shutdown-timeout 0
                                     :error-handler (fn [_]
                                                      {:status 500
                                                       :body "Internal error"})})
      (let [{:keys [body status]} @(http-get (apply str "http://localhost:" port  "/invalid"))]
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

(defn force-stream-to-string-memoization! []
  (bs/to-string (doto (s/stream 1) (s/put! "x") s/close!)))

(deftest test-idle-timeout
  ;; Required so that the conversion initialization doesn't count
  ;; toward the idle timeout. See
  ;; https://github.com/clj-commons/aleph/issues/626 for background.
  (force-stream-to-string-memoization!)
  (let [url (str "http://localhost:" port)
        echo-handler (fn [{:keys [body]}] {:body (bs/to-string body)})
        slow-handler (fn [_] {:body (slow-stream)})]
   (testing "Server is slow to write"
     (with-handler-options slow-handler {:idle-timeout 200
                                         :port port}
       (is (= "012345" (bs/to-string (:body @(http/get url)))))))
   (testing "Server is too slow to write"
     (with-handler-options slow-handler {:idle-timeout 30
                                         :port port}
       (is (= ""
              (bs/to-string (:body @(http/get url)))))))
   (testing "Client is slow to write"
     (with-handler-options echo-handler {:idle-timeout 200
                                         :port port
                                         :raw-stream? true}
       (is (= "012345" (bs/to-string (:body @(http/put url {:body (slow-stream)})))))))
   (testing "Client is too slow to write"
     (with-handler-options echo-handler {:idle-timeout 30
                                         :port port
                                         :raw-stream? true}
       (is (thrown-with-msg? Exception #"connection was close"
                             (bs/to-string (:body @(http/put url {:body (slow-stream)})))))))))
;;;

(deftest test-large-responses
  (with-handler basic-handler
    (dotimes [_ 1 #_1e6]
      #_(when (zero? (rem i 1e2))
          (prn i))
      (-> @(http/get (str "http://localhost:" port "/big")
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
    (.close ^java.io.Closeable server)))

(deftest ^:benchmark benchmark-websockets
  (println "starting WebSocket benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server
                 (fn [req]
                   (d/let-flow [s (http/websocket-connection req)]
                     (s/consume #(s/put! s %) s)))
                 {:port 8080
                  :shutdown-timeout 0})]
    (Thread/sleep (* 1000 60))
    (println "stopping server")
    (.close ^java.io.Closeable server)))


(deftest test-pipeline-header-alteration
  (let [test-header-name "aleph-test"
        test-header-val "MOOP"]
    (with-server (http/start-server
                   basic-handler
                   {:port port
                    :shutdown-timeout 0
                    :pipeline-transform
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
                                      (.write ctx msg p)))))})
      (let [resp @(http-get (str "http://localhost:" port "/string"))]
        (is (= test-header-val (get (:headers resp) test-header-name)))))))

(defn add-http-object-aggregator [^ChannelPipeline pipeline]
  (let [max-content-length 5]
    (.addBefore pipeline
                "request-handler"
                "http-object-aggregator"
                (HttpObjectAggregator. max-content-length))))

(deftest test-http-object-aggregator-support
  (with-server (http/start-server
                basic-handler
                {:port port
                 :shutdown-timeout 0
                 :pipeline-transform add-http-object-aggregator})
    (let [rsp @(http-put (str "http://localhost:" port "/echo")
                         {:body "hello"})]
      (is (= "hello" (bs/to-string (:body rsp))))
      (is (= 200 (:status rsp))))

    (let [rsp @(http-put (str "http://localhost:" port "/echo")
                         {:body "hello, world!"})]
      (is (= 413 (:status rsp)))
      (is (empty? (bs/to-string (:body rsp)))))))

(deftest test-http-object-aggregator-raw-stream-support
  (with-server (http/start-server
                basic-handler
                {:port port
                 :shutdown-timeout 0
                 :raw-stream? true
                 :pipeline-transform add-http-object-aggregator})
    (let [rsp @(http-put (str "http://localhost:" port "/echo")
                         {:body "hello"})]
      (is (= "hello" (bs/to-string (:body rsp))))
      (is (= 200 (:status rsp))))

    (let [rsp @(http-put (str "http://localhost:" port "/echo")
                         {:body "hello, world!"})]
      (is (= 413 (:status rsp)))
      (is (empty? (bs/to-string (:body rsp)))))))

(deftest test-transport
  (testing "epoll"
    (try
      (with-server (http/start-server
                    basic-handler
                    {:port port
                     :shutdown-timeout 0
                     :transport :epoll})
        (let [rsp @(http-put (str "http://localhost:" port "/echo")
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :epoll}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/epoll-available?))))))

  (testing "kqueue"
    (try
      (with-server (http/start-server
                    basic-handler
                    {:port port
                     :shutdown-timeout 0
                     :transport :kqueue})
        (let [rsp @(http-put (str "http://localhost:" port "/echo")
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :kqueue}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/kqueue-available?))))))

  (testing "io-uring"
    (try
      (with-server (http/start-server
                    basic-handler
                    {:port port
                     :shutdown-timeout 0
                     :transport :io-uring})
        (let [rsp @(http-put (str "http://localhost:" port "/echo")
                             {:body "hello"
                              :pool (http/connection-pool {:connection-options {:transport :io-uring}})})]
          (is (= 200 (:status rsp)))
          (is (= "hello" (bs/to-string (:body rsp))))))
      (catch Exception _
        (is (not (netty/io-uring-available?)))))))

(deftest test-max-request-body-size
  (testing "max-request-body-size of 0"
    (with-handler-options (constantly {:body "OK"})
      {:port port
       :max-request-body-size 0}
      (let [resp @(http-put (str "http://localhost:" port)
                            {:body "hello"})]
        (is (= 413 (:status resp))))))

  (testing "max-request-body-size of 1"
    (with-handler-options (constantly {:body "OK"})
      {:port port
       :max-request-body-size 1}
      (let [resp @(http-put (str "http://localhost:" port)
                            {:body "hello"})]
        (is (= 413 (:status resp))))))

  (testing "max-request-body-size of 6"
    (with-handler-options (constantly {:body "OK"})
      {:port port
       :max-request-body-size 6}
      (let [resp @(http-put (str "http://localhost:" port)
                            {:body "hello"})]
        (is (= 200 (:status resp)))))))

(deftest test-text-plain-charset
  (with-server (http/start-server
                basic-handler
                {:port port
                 :shutdown-timeout 0})
    (let [resp @(http-get (str "http://localhost:" port "/text_plain"))]
      (is (= "text/plain; charset=UTF-8" (-> resp :headers (get "Content-Type"))))
      (is (= 200 (:status resp)))
      (is (= "Hello" (bs/to-string (:body resp)))))))

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
      (let [rsp (-> (http-post (str "http://localhost:" port))
                    (d/timeout! 1e3)
                    deref)]
        (is (= 4000001 (:status rsp)))
        (is (= "" (bs/to-string (:body rsp))))))) ;; if the body is invalid, empty is returned with the current Aleph behaviour

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
          (.shutdown ^Pool pool))))))

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
                                                     :shutdown-timeout 0
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
      (is (.startsWith ^String *response* "HTTP/1.1 400 Bad Request"))))

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
    (with-tcp-request-handler
      (fn [req]
        (try
          (bs/to-string (:body req))
          (catch Throwable _))
        {:status 201
         :body "not good"})
      {:request-buffer-size 1}
      ["POST / HTTP/1.1"
       "transfer-encoding: chunked"
       ""
       "not-a-number" ;; definitely not parseable chunk size
       "fail"
       "0"]
      (is (.startsWith *response* "HTTP/1.1 201 Created"))))

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
          (is (= "" (bs/to-string (:body resp)))))))))
