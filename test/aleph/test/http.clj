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
    [aleph.http.client :only (http-connection)]
    [lamina core connections trace]
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]]
    [clojure.contrib.seq :only [indexed]])
  (:require
    [clojure.string :as str]
    [clojure.contrib.logging :as log])
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

(defn print-vals [& args]
  (apply prn args)
  (last args))

(defn basic-handler [ch request]
  (when-let [handler (route-map (:uri request))]
    (enqueue ch (handler request))))

(def expected-results
  (->>
    ["string" string-response
     "stream" stream-response
     "seq" (apply str seq-response)]
    (repeat 10)
    (apply concat)
    (partition 2)))

;;;

(defn streaming-request-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type (:content-type request)
     :body (->> request :body (map str) (apply closed-channel))}))

(defn json-response-handler [ch request]
  (enqueue ch
    {:status 200
     :content-type "application/json"
     :body {:foo 1 :bar 2}}))

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
		  {:port 8080,
		   :probes {;;:calls log-info
			    ;;:results log-info
			    :errors (siphon->> (map* :exception) log-error)
			    }
		   :auto-transform true
		   })
     ~@body))

(defn is-closed? [handler & requests]
  (with-handler handler
    (let [connection @(http-connection {:url "http://localhost:8080"})]
      (apply enqueue connection requests)
      (doall (lazy-channel-seq connection 1000))
      (is (closed? connection)))))

;;;

#_(deftest test-browser-http-response
    (println "waiting for browser test")
    (reset! browser-server (start-http-server basic-handler {:port 8080}))
    (is @latch))

(deftest test-single-requests
  (with-handler basic-handler
    (doseq [[index [path result]] (indexed expected-results)]
      (let [client (http-client {:url "http://localhost:8080", :auto-transform true})]
	(try
	  (is (= result (wait-for-request client path)))
	  (finally
	    (close-connection client)))))))

(deftest test-multiple-requests
  (with-handler basic-handler
    (let [client (http-client {:url "http://localhost:8080", :auto-transform true})]
      (doseq [[index [path result]] (indexed expected-results)]
	(is (= result (wait-for-request client path))))
      (close-connection client))))

(deftest test-streaming-response
  (with-handler streaming-request-handler
    (let [content "abcdefghi"
	  client (http-client {:url "http://localhost:8080", :auto-transform true})]
      (try
	(dotimes [_ 3]
	  (is
	    (= content
	      (:body
		(wait-for-result
		  (client {:url "http://localhost:8080"
			   :method :post
			   :auto-transform true
			   :headers {"content-type" "text/plain"}
			   :body (apply closed-channel (map str content))})
		  1000)))))
	(finally
	  (close-connection client))))))

(deftest test-auto-transform
  (with-handler json-response-handler
    (let [result (sync-http-request {:url "http://localhost:8080", :method :get, :auto-transform true} 1000)]
      (is (= {:foo 1, :bar 2} (:body result))))))

(deftest test-websockets
  (with-server (start-http-server (fn [ch _] (siphon ch ch)) {:port 8080, :websocket true})
    (let [result (run-pipeline (websocket-client {:url "http://localhost:8080"})
		   (fn [ch]
		     (enqueue ch "a" "b" "c")
		     ch))]
      (is (= ["a" "b" "c"] (channel-seq @result 1000)))
      (close @result))))

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
