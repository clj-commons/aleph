;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http
  (:use
    [aleph http]
    [lamina core connections trace api]
    [clojure.test]
    [aleph.test.utils])
  (:require
    [aleph.formats :as formats]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    [java.util.concurrent
     TimeoutException]
    [java.io
     File
     ByteArrayInputStream
     StringReader
     PushbackReader]))

;;;

(def string-response "String!")
(def seq-response ["sequence: " 1 " two " 3.0])
(def file-response (File. (str (System/getProperty "user.dir")
                               "/test/file.txt")))
(def stream-response "Stream!")

(defn string-handler [request]
  {:status 200
   :content-type "text/html"
   :body string-response})

(defn seq-handler [request]
  {:status 200
   :content-type "text/html"
   :body seq-response})

(defn file-handler [request]
  {:status 200
   :content-type "text/plain"
   :body file-response})

(defn stream-handler [request]
  {:status 200
   :content-type "text/html"
   :body (ByteArrayInputStream. (.getBytes stream-response))})



(def latch (promise))
(def browser-server (atom nil))

(def route-map
  {"/stream" stream-handler
   "/file" file-handler
   "/seq" seq-handler
   "/string" string-handler
   "/stop" (fn [_]
	     (try
	       (deliver latch true) ;;this can be triggered more than once, sometimes
	       (@browser-server)
	       (catch Exception e
		 )))})

(defn basic-handler [ch request]
  (when-let [handler (route-map (:uri request))]
    (enqueue ch (handler request))))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)
     "file" "this is a file"]
    (repeat 10)
    (apply concat)
    (partition 2)))

;;;

(defn streaming-request-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type (:content-type request)
     :body (:body request)}))

(defn ring-streaming-request-handler [request]
  {:status 200
   :content-type (:content-type request)
   :body (:body request)})

(defn json-response-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type "application/json; charset=UTF-8"
     :body (formats/encode-json->string {:foo 1 :bar 2})}))

(defn error-aleph-handler [ch request]
  (throw (Exception. "boom!")))

(defn async-error-aleph-handler [ch request]
  (error-result (Exception. "async boom!")))

(defn timeout-aleph-handler [ch request]
  (throw (TimeoutException.)))

(defn async-timeout-aleph-handler [ch request]
  (error-result (TimeoutException.)))

(defn error-ring-handler [request]
  (throw (Exception. "boom!")))

(defn async-error-ring-handler [request]
  (error-result (Exception. "async boom!")))

(defn timeout-ring-handler [request]
  (throw (TimeoutException.)))

(defn async-timeout-ring-handler [request]
  (error-result (TimeoutException.)))

(defn default-http-client []
  (http-client
    {:url "http://localhost:8080"
     :auto-decode? true}))

;;;

(defn wait-for-request [client path]
  (-> (client {:method :get, :url (str "http://localhost:8080/" path), :auto-transform true})
    (wait-for-result 500)
    :body))

(defmacro with-server [server & body]
  `(let [kill-fn# ~server]
     (try
       ~@body
       (finally
	 (kill-fn#)))))

(defmacro with-handler [handler & body]
  `(with-server (start-http-server ~handler
		  {:port 8080
                   :websocket true
		   :probes {:error (sink (fn [& _#]))}})
     ~@body))

(defmacro with-handlers [[aleph-handler ring-handler] & body]
  `(do
     (with-handler ~aleph-handler
       ~@body)
     (with-handler (wrap-ring-handler ~ring-handler)
       ~@body)))

(defn is-closed? [handler & requests]
  (with-handler handler
    (let [connection @(http-connection {:url "http://localhost:8080"})]
      (apply enqueue connection requests)
      (try
	(doall (lazy-channel-seq connection 1000))
	(catch Exception e))
      (is (closed? connection)))))

(defn test-handler-response [expected aleph-handler ring-handler]
  (with-handlers [aleph-handler ring-handler]
    (is (= expected (:status (sync-http-request {:method :get, :url "http://localhost:8080"} 1000))))
    (is (= expected (:status (sync-http-request {:method :get, :url "http://localhost:8080", :keep-alive? true} 1000))))))

;;;

(deftest test-error-responses
  (test-handler-response 500 error-aleph-handler error-ring-handler)
  (test-handler-response 500 async-error-aleph-handler async-error-ring-handler)
  (test-handler-response 408 timeout-aleph-handler timeout-ring-handler)
  (test-handler-response 408 async-timeout-aleph-handler async-timeout-ring-handler))

#_(deftest test-browser-http-response
    (println "waiting for browser test")
    (reset! browser-server (start-http-server basic-handler {:port 8080}))
    (is @latch))

(deftest test-single-requests
  (with-handler basic-handler
    (doseq [[index [path result]] (map vector (iterate inc 0) expected-results)]
      (let [client (default-http-client)]
	(try
	  (is (= result (wait-for-request client path)))
	  (finally
	    (close-connection client)))))))

(deftest test-multiple-requests
  (with-handler basic-handler
    (let [client (default-http-client)]
      (doseq [[index [path result]] (map vector (iterate inc 0) expected-results)]
	(is (= result (wait-for-request client path))))
      (close-connection client))))

(deftest test-streaming-response
  (with-handlers [streaming-request-handler ring-streaming-request-handler]
    (let [content "abcdefghi"
	  client (default-http-client)]
      (try
	(dotimes [_ 3]
	  (let [response (wait-for-result
                           (client {:url "http://localhost:8080"
                                    :method :post
                                    :auto-transform true
                                    :headers {"content-type" "text/plain"}
                                    :body (apply closed-channel (map str content))})
                           2000)]
            (is (= content (:body response)))))
	(finally
	  (close-connection client))))))

(deftest test-auto-transform
  (with-handler json-response-handler
    (let [result (sync-http-request
                   {:url "http://localhost:8080", :method :get, :auto-transform true}
                   1000)]
      (is (= {:foo 1, :bar 2} (:body result))))))

(deftest test-single-response-close
  (is-closed? basic-handler
    {:method :get, :url "http://localhost:8080/string", :keep-alive? false}))

(deftest test-streaming-request-close
  (is-closed? streaming-request-handler
    {:method :post
     :url "http://localhost:8080/"
     :content-encoding "text/plain"
     :body (closed-channel "a" "b" "c")
     :keep-alive? false}))

(deftest test-multiple-response-close
  (is-closed? basic-handler
    {:method :get, :url "http://localhost:8080/string", :keep-alive? true}
    {:method :get, :url "http://localhost:8080/string", :keep-alive? false}))

;;;

(defn hello-world-handler [ch request]
  (enqueue ch {:status 200, :body "hello"}))

(deftest ^:benchmark run-http-benchmark
  (with-handler hello-world-handler
    (let [create-conn #(deref (http-connection {:url "http://localhost:8080"}))]

      (let [ch (create-conn)]
        (bench "http hello-world"
          (enqueue ch {:method :get})
          @(read-channel ch))
        (close ch)))))
