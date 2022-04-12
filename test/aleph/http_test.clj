(ns aleph.http-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest testing is]]
    [aleph
     [http :as http]
     [netty :as netty]
     [flow :as flow]
     [ssl :as ssl]
     [tcp :as tcp]]
    [aleph.http.core :as core]
    [clj-commons.byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (java.io
      File)
    (java.util.zip
      GZIPInputStream
      ZipException)
    (java.util.concurrent
      TimeoutException)
    (aleph.utils
      ConnectionTimeoutException
      RequestTimeoutException)
    (io.netty.channel
      ChannelHandlerContext
      ChannelOutboundHandlerAdapter
      ChannelPipeline
      ChannelPromise)
    (io.netty.handler.codec.http
      HttpMessage)))

;;;

(set! *warn-on-reflection* false)

(def ^:dynamic ^io.aleph.dirigiste.IPool *pool* nil)

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
     (binding [*pool* (http/connection-pool {:connection-options {:insecure? true}})]
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

(def default-ssl-options {:port port, :ssl-context (netty/self-signed-ssl-context)})

(defmacro with-handler-options
   [handler options & body]
   `(with-server (http/start-server ~handler ~options)
      ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port port, :raw-stream? true})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[index [path result]] (map-indexed vector expected-results)]
      (is
        (= result
          (bs/to-string
            (:body
              @(http-get (str "http://localhost:" port "/" path)))))))))

(deftest test-compressed-response
  (with-compressed-handler basic-handler
    (doseq [[index [path result]] (map-indexed vector expected-results)
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
    (doseq [[index [path result]] (map-indexed vector expected-results)]
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
  (let [long-url (apply str "http://localhost:" port  "/" (repeat 1e4 "a"))]
    (with-handler basic-handler
      (is (= 414 (:status @(http-get long-url)))))))

(deftest test-overly-long-header
  (let [url (str "http://localhost:" port)
        long-header-value (apply str (repeat 1e5 "a"))
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

(defn echo-handler [req]
  {:status 200
   :body (:body req)})

(deftest test-trace-request-omitted-body
  (with-handler echo-handler
    (is (= "" (-> @(http/trace (str "http://localhost:" port) {:body "REQUEST"})
                  :body
                  bs/to-string)))))

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


(deftest test-pipeline-header-alteration
  (let [test-header-name "aleph-test"
        test-header-val "MOOP"]
    (with-server (http/start-server
                   basic-handler
                   {:port port
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
