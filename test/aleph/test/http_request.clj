(ns aleph.test.http-request
  (:use [aleph] :reload-all)
  (:use [clojure.test])
  (:require clojure.contrib.http.agent))

(def port (atom 8080))

(defn create-url [host port path]
  (str "http://" host ":" port "/" path))

(defn make-http-request [url method]
  (clojure.contrib.http.agent/string
    (clojure.contrib.http.agent/http-agent url :method method)))

; The return-* methods are helper handlers defined to return the relevant 
; portion of an http request as text. We then inspect that value when returned 
; to validate correctness.
(defn return-request-method [request]
  (respond! request
    {:status 200
     :headers {"Content-Type", "text/plain"}
     :body (str (:request-method request))}))

(defn return-scheme [request]
  (respond! request
    {:status 200
     :headers {"Content-Type", "text/plain"}
     :body (str (:scheme request))}))

(defn return-uri [request]
  (respond! request
    {:status 200
     :headers {"Content-Type", "text/plain"}
     :body (str (:uri request))}))

(defn return-query-string [request]
  (respond! request
    {:status 200
     :headers {"Content-Type", "text/plain"}
     :body (if (= nil (:query-string request))
               "nil"
               (:query-string request))}))

; Tests all of the different possible values of :request-method
(deftest test-request-method
  (let [test-port (swap! port inc)
        server (run-aleph return-request-method {:port test-port})]

    (testing "get"
      (is (= ":get" (make-http-request 
                      (create-url "localhost" test-port "") 
                      "GET"))))

    (testing "post"
      (is (= ":post" (make-http-request 
                      (create-url "localhost" test-port "") 
                      "POST"))))
                      
    (testing "put"
      (is (= ":put" (make-http-request 
                      (create-url "localhost" test-port "") 
                      "PUT"))))

    (testing "delete"
      (is (= ":delete" (make-http-request 
                        (create-url "localhost" test-port "") 
                        "DELETE"))))

    (testing "head"
      ; by default, head isn't returning a content body
      ; maybe a separate method that checks a custom header field?
      (is (= "" (make-http-request 
                  (create-url "localhost" test-port "") 
                  "HEAD"))))

    (stop server)))

; Tests the different options for :scheme.  Right now only :http is checked for
; TODO: Eventually, we need to also test for https
(deftest test-scheme
  (let [test-port (swap! port inc)
        server (run-aleph return-scheme {:port test-port})]

    (testing "scheme"
      (is (= ":http" (make-http-request 
                        (create-url "localhost" test-port "") 
                        "GET"))))

    (stop server)))

; Tests the parsing of :uri, to ensure that it's only the section after the 
; host and before the query string
(deftest test-uri
  (let [test-port (swap! port inc)
        server (run-aleph return-uri {:port test-port})]

    (testing "root"
      (is (= "/" (make-http-request 
                    (create-url "localhost" test-port "") 
                    "GET"))))

    (testing "single directory"
      (is (= "/testdir" (make-http-request 
                          (create-url "localhost" test-port "testdir") 
                          "GET"))))

    (testing "multiple directories"
      (is (= "/testdir/testdir2" 
              (make-http-request 
                (create-url "localhost" test-port "testdir/testdir2") 
                "GET"))))

    (testing "trailing slash"
      (is (= "/testdir/" (make-http-request 
                           (create-url "localhost" test-port "testdir/") 
                           "GET"))))

    (stop server)))

; Tests the parsing of :query-string.  Should be the full string after the 
; first "?" in the URL or nil in the case that no query string is part of the 
; full url
(deftest test-query-string
  (let [test-port (swap! port inc)
        server (run-aleph return-query-string {:port test-port})]

    (testing "no query string"
      (is (= "nil" (make-http-request 
                    (create-url "localhost" test-port "") 
                    "GET"))))

    (testing "single value"
      (is (= "test" (make-http-request 
                      (create-url "localhost" test-port "?test") 
                      "GET"))))

    (testing "single key value pair"
      (is (= "test=testval" (make-http-request 
                              (create-url "localhost" test-port "?test=testval")
                              "GET"))))

    (testing "multiple key value pairs"
      (is (= "t1=tv1&t2=tv2" 
              (make-http-request 
                (create-url "localhost" test-port "?t1=tv1&t2=tv2") 
                "GET"))))

    (stop server)))
