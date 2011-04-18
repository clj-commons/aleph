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
    [lamina core connections]
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]]
    [clojure.contrib.seq :only [indexed]])
  (:require
    [clojure.string :as str])
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
   :content-type "text/html"
   :body string-response})

(defn seq-handler [request]
  {:status 200
   :content-type "text/html"
   :body seq-response})

(defn file-handler [request]
  {:status 200
   :body file-response})

(defn stream-handler [request]
  {:status 200
   :content-type "text/html"
   :body (ByteArrayInputStream. (.getBytes stream-response))})

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

(defn basic-handler [ch request]
  (when-let [handler (route-map (:uri request))]
    (enqueue-and-close ch
      (handler request))))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))

;;;

(defn streaming-response-handler [ch request]
  (let [body (apply closed-channel (map str "abcdefghi"))]
    (enqueue ch
      {:status 200
       :content-type "text/plain"
       :body body})))

(defn streaming-request-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type (:content-type request)
     :body (:body request)}))

(defn json-response-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type "application/json"
     :body {:foo 1 :bar 2}}))

;;;

(defn wait-for-request [client path]
  (-> (client {:method :get, :url (str "http://localhost:8080/" path), :auto-transform true})
    (wait-for-result 1000)
    :body))

(defmacro with-server [handler & body]
  `(do
     (let [kill-fn# (start-http-server ~handler {:port 8080, :auto-transform true})]
      (try
	~@body
	(finally
	  (kill-fn#))))))

'(deftest browser-http-response
   (println "waiting for browser test")
   (start-http-server basic-handler {:port 8080})
   (is @latch))

(deftest single-requests
  (with-server basic-handler
    (doseq [[index [path result]] (indexed expected-results)]
      (let [client (http-client {:url "http://localhost:8080", :auto-transform true})]
	(is (= result (wait-for-request client path)))
	(close-connection client)))))

(deftest multiple-requests
  (with-server basic-handler
    (let [client (http-client {:url "http://localhost:8080", :auto-transform true})]
      (doseq [[index [path result]] (indexed expected-results)]
	(is (= result (wait-for-request client path))))
      (close-connection client))))

(deftest streaming-response
  (with-server streaming-response-handler
    (let [result (sync-http-request {:url "http://localhost:8080", :method :get, :auto-transform true} 1000)]
      (is
	(= (map str "abcdefghi")
	   (channel-seq (:body result) 1000))))))

(deftest streaming-request
  (let [s (map (fn [n] {:tag :value, :attrs nil, :content [(str n)]}) (range 10))]
    (doseq [content-type ["application/json" "application/xml"]]
      (with-server streaming-request-handler
	(let [ch (apply closed-channel s)]
	  (let [result (sync-http-request
			 {:url "http://localhost:8080"
			  :method :post
			  :headers {"content-type" content-type}
			  :body ch,
			  :auto-transform true}
			 1000)]
	    (let [transform-results (fn [x] (map #(-> % :content first str/trim) x))]
	      (is (= (transform-results s) (transform-results (channel-seq (:body result) 1000)))))))))))

(deftest auto-transform-test
  (with-server json-response-handler
    (let [result (sync-http-request {:url "http://localhost:8080", :method :get, :auto-transform true} 1000)]
      (is (= {:foo 1, :bar 2} (:body result))))))

(deftest websocket-server
  (with-server (start-http-server (fn [ch _] (siphon ch ch)) {:port 8081, :websocket true})
    (let [result (run-pipeline (websocket-client {:url "http://localhost:8081"})
		   (fn [ch]
		     (enqueue ch "abc")
		     (read-channel ch 1000)))]
      (is (= "abc" (wait-for-result result 1000))))))
