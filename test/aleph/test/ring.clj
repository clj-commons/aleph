;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.ring
  (:use
    [lamina.core]
    [aleph http])
  (:use [clojure test pprint])
  (:import [java.io StringReader PushbackReader]))

(defn create-url
  ([path]
     (create-url 8080 path))
  ([port path]
     (create-url "http" "localhost" port path))
  ([scheme host port path]
     (str scheme "://" host ":" port path)))

(defn request
  ([& {:as options}]
     (let [options (merge
		     {:url (create-url "/")
		      :method :get}
		     options)]
       (->> (sync-http-request
	      {:request-method (:method options)
	       :url (:url options)
	       :auto-transform true
	       :headers (:headers options)
	       :body (:body options)}
	      500)
	 :body
	 read-string))))

(defn get-request-value [request keys]
  (reduce
    #(or (get %1 %2) (get %1 (keyword %2)))
    request
    keys))

(defn request-callback [keys]
  (wrap-ring-handler
    (fn [request]
      {:status 200
       :headers {"content-type" "text/plan"}
       :body (with-out-str
	       (write
		 (get-request-value request keys)))})))

(defmacro with-server [keys & body]
  `(let [kill-fn# (start-http-server (request-callback ~keys) {:port 8080})]
     (try
       ~@body
       (finally
	 (kill-fn#)))))

;;;

(deftest test-request-method
  (with-server [:request-method]
    (doseq [method [:get :post :put :delete]]
      (is (= method (request :method method))))))

(deftest test-scheme
  (with-server [:scheme]
    (is (= :http (request)))))

(deftest test-uri
  (with-server [:uri]
    (doseq [uri ["/a" "/a/b" "/a/b/c/"]]
      (is (= uri (request :url (create-url uri)))))))

(deftest test-query-string
  (with-server [:query-string]
    (doseq [[k v] {"/" nil
		   "/a" nil
		   "/a?a=b" "a=b"
		   "/a?a=b&c=d" "a=b&c=d"}]
      (is (= v (request :url (create-url k)))))))

(deftest test-server-name
  (with-server [:server-name]
    (is (= "localhost" (request)))))

(deftest test-server-port
  (with-server [:server-port]
    (is (= 8080 (request)))))

(deftest test-remote-addr
  (with-server [:remote-addr]
    (is (= "127.0.0.1" (request)))))

(deftest test-content-length
  (with-server [:content-length]
    (is (= 5 (request :body "hello")))))

(deftest test-content-type
  (with-server [:content-type]
    (is (= "text/plain; charset=utf-8"
	   (request :headers {"content-type" "text/plain; charset=utf-8"})))))

(deftest test-character-encoding
  (with-server [:character-encoding]
    (is (= "utf-8" (request :headers {"content-type" "text/plain; charset=utf-8"})))))
