;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http
  (:use [aleph core http] :reload-all)
  (:use
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]]
    [clojure.contrib.seq :only [indexed]])
  (:import
    [java.io
     File
     ByteArrayInputStream
     StringReader
     PushbackReader]))

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
	       (stop-server @server)
	       (catch Exception e
		 )))})

(defn create-handler []
  (fn [ch request]
    (when-let [handler (route-map (:uri request))]
      (enqueue-and-close ch
	(handler request)))))

(defn request [client path]
  (http-request client {:request-method :get, :url (str "http://localhost:8080/" path)}))

(defn wait-for-request [client path]
  (-> (request client path) wait-for-pipeline wait-for-message :body))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)]
    (repeat 500)
    (apply concat)
    (partition 2)))

(defmacro with-server [& body]
  `(let [kill-fn# (start-http-server (create-handler) {:port 8080})]
     (try
       ~@body
       (finally
	 (kill-fn#)))))

'(deftest browser-http-response
  (let [server (reset! server (start-http-server (create-handler) {:port 8080}))]
    (is @latch)))

(deftest single-requests
  (with-server
    (doseq [[path result] (take 50 expected-results)]
      (let [client (raw-http-client {:url "http://localhost:8080"})]
	(is (= result (wait-for-request client path)))
	(close-http-client client)))))

(deftest multiple-requests
  (with-server
    (let [client (raw-http-client {:url "http://localhost:8080"})]
      (doseq [[index [path result]] (indexed expected-results)]
	(is (= result (wait-for-request client path))))
      (close-http-client client))))


