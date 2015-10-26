(ns aleph.http-test
  (:use
    [clojure test])
  (:require
    [clojure.java.io :as io]
    [aleph
     [netty :as netty]
     [flow :as flow]]
    [byte-streams :as bs]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [aleph.http :as http])
  (:import
    [java.util.concurrent
     Executors]
    [java.io
     File
     ByteArrayInputStream]
    [java.util.concurrent
     TimeoutException]))

(netty/leak-detector-level! :paranoid)

(def string-response "String!")
(def seq-response (map identity ["sequence: " 1 " two " 3.0]))
(def file-response (File. (str (System/getProperty "user.dir") "/test/file.txt")))
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

(defn stream-handler [request]
  {:status 200
   :body (bs/to-input-stream stream-response)})

(defn slow-handler [request]
  {:status 200
   :body (cons "1" (lazy-seq (do (Thread/sleep 1000) '("2"))))})

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
                              ":8080/redirect?count=" (dec count))}
       :body "redirected!"})))

(def latch (promise))
(def browser-server (atom nil))

(def route-map
  {"/redirect" redirect-handler
   "/stream" stream-handler
   "/slow" slow-handler
   "/file" file-handler
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
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally
         (.close ^java.io.Closeable server#)))))

(defmacro with-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080})
     ~@body))

(defmacro with-raw-handler [handler & body]
  `(with-server (http/start-server ~handler {:port 8080, :raw-stream? true})
     ~@body))

(defmacro with-both-handlers [handler & body]
  `(do
     (with-handler ~handler ~@body)
     (with-raw-handler ~handler ~@body)))

;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[index [path result]] (map vector (iterate inc 0) expected-results)]
      (is
        (= result
          (bs/to-string
            (:body
              @(http/get (str "http://localhost:8080/" path)
                 {:socket-timeout 1000}))))))))

(def words (slurp "/usr/share/dict/words"))

(deftest test-bulk-requests
  (with-handler basic-handler
    (->> (range 1e3)
      (map (fn [_] (http/get "http://localhost:8080/string")))
      (apply d/zip)
      deref)
    (dotimes [_ 1e2]
      (->> (range 1e2)
        (map (fn [_] (http/get "http://localhost:8080/string")))
        (apply d/zip)
        deref))))

(deftest test-echo
  (with-both-handlers basic-handler
    (doseq [len [1e3 1e4 1e5 1e6 1e7]]
      (let [words (->> words (take len) (apply str))
            body (:body
                   @(http/put "http://localhost:8080/echo"
                      {:body words
                       :socket-timeout 2000}))
            body' (bs/to-string body)]
        (assert (== (min (count words) len) (count body')))
        (is (= words body'))))))

(deftest test-redirect
  (with-both-handlers basic-handler
    (is (= "ok"
          (-> @(http/get "http://localhost:8080/redirect?count=10")
            :body
            bs/to-string)))
    (is (= "redirected!"
          (-> @(http/get "http://localhost:8080/redirect?count=25")
            :body
            bs/to-string)))
    (is (= "ok"
          (-> @(http/get "http://localhost:8080/redirect?count=25" {:max-redirects 30})
            :body
            bs/to-string)))))

(deftest test-middleware
  (with-both-handlers basic-handler
    (is (= "String!"
          (-> @(http/get "http://localhost:8080/stream"
                 {:middleware
                  (fn [client]
                    (fn [req]
                      (client (assoc req :url "http://localhost:8080/string"))))})
            :body
            bs/to-string)))))

(deftest test-line-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5]]
      (let [words (->> words (take len) (apply str))
            body (:body
                   @(http/put "http://localhost:8080/line_echo"
                      {:body words
                       :socket-timeout 1e4}))]
        (is (= (.replace ^String words "\n" "") (bs/to-string body)))))))

(deftest test-illegal-character-in-url
  (with-handler hello-handler
    (is (= "hello"
           (-> @(http/get "http://localhost:8080/?param=illegal character")
               :body
               bs/to-string)))))

(deftest test-connection-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http/get "http://192.0.2.0" ;; "TEST-NET" in RFC 5737
             {:connection-timeout 2})))))

(deftest test-request-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http/get "http://localhost:8080/slow"
             {:request-timeout 5})))))

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
            @(http/get "http://localhost:8080/string")))))
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
