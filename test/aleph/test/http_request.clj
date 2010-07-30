;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.http-request
  (:use [aleph core http] :reload-all)
  (:use [clojure.test]))

(def body "Hello world!")

(defn hello-world [channel request]
  (enqueue-and-close channel
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body body}))

(deftest test-get
  (let [server (start-http-server hello-world {:port 8080})]
    (try
      (let [response (->>
		       (run-pipeline
			 (http-client {:host "localhost" :port 8080})
			 (fn [ch]
			   (enqueue ch {:request-method :get, :uri "/"})
			   ch))
		       wait-for-pipeline
		       wait-for-message)]
	(is (= (:body response) body)))
      (finally
	(stop-server server)))))
