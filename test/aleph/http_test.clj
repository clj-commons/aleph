;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http-test
  (:use
    [clojure test])
  (:require
    [byte-streams :as bs]
    [aleph.http :as http]
    [clj-http.client :as client])
  (:import
    [java.io
     File
     ByteArrayInputStream]))

(def string-response "String!")
(def seq-response ["sequence: " 1 " two " 3.0])
(def file-response (File. (str (System/getProperty "user.dir") "/test/file.txt")))
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
   :body (bs/to-input-stream stream-response)})

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

;;;

(deftest test-response-formats
  (with-handler basic-handler
    (doseq [[index [path result]] (map vector (iterate inc 0) expected-results)]
      (= result
        (:body
          (client/get (str "http://localhost:8080/" path)
            {:socket-timeout 1000}))))))
