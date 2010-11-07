;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http
  (:use [aleph http])
  (:use
    [lamina.core]
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]]
    [clojure.contrib.seq :only [indexed]])
  (:import
    [java.io
     File
     ByteArrayInputStream
     StringReader
     PushbackReader]))

;;;

(def string-response "String!")
(def seq-response ["sequence: " 1 " two " 3.0])
(def file-response (File. (str (pwd) "/test/starry_night.jpg")))
(def stream-response "Stream!")

(defn string-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body string-response})

(defn seq-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body seq-response})

(defn file-handler [request]
  {:status 200
   :body file-response})

(defn stream-handler [request]
  {:status 200
   :header {"content-type" "text/html"}
   :body (ByteArrayInputStream. (.getBytes stream-response))})

(def server (atom nil))
(def latch (promise))

(def route-map
  {"/stream" stream-handler
   "/file" file-handler
   "/seq" seq-handler
   "/string" string-handler
   "/stop" (fn [_]
	     (try
	       (deliver latch true) ;;this can be triggered more than once, sometimes
	       (@server)
	       (catch Exception e
		 )))})

(defn create-basic-handler []
  (let [num (atom -1)]
    (fn [ch request]
      (when-let [handler (route-map (:uri request))]
	(enqueue-and-close ch
	  (handler request))))))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))

;;;

(defn create-streaming-response-handler []
  (fn [ch request]
    (let [body (apply sealed-channel (map str "abcdefghi"))]
      (enqueue ch
	{:status 200
	 :headers {"content-type" "text/plain"}
	 :body body}))))

(defn create-streaming-request-handler []
  (fn [ch request]
    (enqueue ch
      {:status 200
       :headers {"content-type" "application/json"}
       :body (:body request)})))

;;;

(defn request [client path]
  (http-request client {:request-method :get, :url (str "http://localhost:8080/" path)}))

(defn wait-for-request [client path]
  (-> (request client path)
    (wait-for-pipeline 1000)
    :body))

(defmacro with-server [handler & body]
  `(let [kill-fn# (start-http-server ~handler {:port 8080})]
     (try
       ~@body
       (finally
	 (kill-fn#)))))

'(deftest browser-http-response
   (println "waiting for browser test")
   (let [server (reset! server (start-http-server (create-basic-handler) {:port 8080}))]
     (is @latch)))

(deftest single-requests
  (with-server (create-basic-handler)
    (doseq [[index [path result]] (indexed expected-results)]
      (let [client (http-client {:url "http://localhost:8080"})]
	(is (= result (wait-for-request client path)))
	(close-http-client client)))))

(deftest multiple-requests
  (with-server (create-basic-handler)
    (let [client (http-client {:url "http://localhost:8080"})]
      (doseq [[index [path result]] (indexed expected-results)]
	(is (= result (wait-for-request client path))))
      (close-http-client client))))

(deftest streaming-response
  (with-server (create-streaming-response-handler)
    (let [result (sync-http-request {:url "http://localhost:8080", :method :get})]
      (is
	(= (map str "abcdefghi")
	   (channel-seq (:body result) -1))))))

(deftest streaming-request
  (with-server (create-streaming-request-handler)
    (let [ch (apply sealed-channel (range 10))]
      (let [result (sync-http-request
		     {:url "http://localhost:8080"
		      :method :post
		      :headers {"content-type" "application/json"}
		      :body ch})]
	(is
	  (= (range 10)
	     (channel-seq (:body result) -1)))))))


