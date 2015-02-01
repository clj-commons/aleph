(ns aleph.http-test
  (:use
    [clojure test])
  (:require
    [clojure.java.io :as io]
    [aleph.netty :as netty]
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

(def latch (promise))
(def browser-server (atom nil))

(def route-map
  {"/stream" stream-handler
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
               (catch Exception e
                 )))})

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
     "seq" (apply str seq-response)
     ]
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
                       :socket-timeout 2000}))]
        (is (= words (bs/to-string body)))))))

(deftest test-line-echo
  (with-handler basic-handler
    (doseq [len [1e3 1e4 1e5]]
      (let [words (->> words (take len) (apply str))
            body (:body
                   @(http/put "http://localhost:8080/line_echo"
                      {:body words
                       :socket-timeout 2000}))]
        (is (= (.replace ^String words "\n" "") (bs/to-string body)))))))

(deftest test-connection-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http/get "http://8.8.8.8"
             {:connection-timeout 2})))))

(deftest test-request-timeout
  (with-handler basic-handler
    (is (thrown? TimeoutException
          @(http/get "http://localhost:8080/slow"
             {:request-timeout 5})))))

;;;

(deftest ^:benchmark benchmark-http
  (println "starting HTTP benchmark server on 8080")
  (netty/leak-detector-level! :disabled)
  (let [server (http/start-server hello-handler {:port 8080})]
    (Thread/sleep (* 1000 120))
    (println "stopping server")
    (.close ^java.io.Closeable server)))
