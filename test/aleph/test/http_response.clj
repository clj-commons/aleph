;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http-response
  (:use [aleph core http] :reload-all)
  (:use
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]])
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

(defn handler [ch request]
  (when-let [handler (route-map (:uri request))]
    (enqueue-and-close ch
      (handler request))))

'(deftest browser-http-response
  (let [server (reset! server (start-http-server handler {:port 8080}))]
    (is @latch)))

(defn request [path]
  (->> (http-request {:request-method :get, :url (str "http://localhost:8080/" path)})
    run-pipeline))

(def expected-results
  (->>
    {"string" string-response
    "stream" stream-response
    "seq" (apply str seq-response)}
    (repeat 100)
    concat
    (apply concat)
    (partition 2)))

(deftest )

(deftest http-response
  (let [kill-fn (start-http-server handler {:port 8080})]
    (try
      (let [request-response (->> expected-results
				   (repeat 100)
				   concat
				   (apply concat)
				   (partition 2))
	    ]
	)
      (doseq [[request response]])
      (dotimes [_ 100]
	(is (= string-response (request "string")))
	(is (= stream-response (request "stream")))
	(is (= (apply str seq-response) (request "seq"))))
      ;;need test for file here
      (finally
	(kill-fn)))))
