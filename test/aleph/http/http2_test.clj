(ns aleph.http.http2-test
  (:require [aleph.http.http2 :refer :all]
            [clojure.test :refer :all])
  (:import (io.netty.handler.codec.http HttpResponseStatus)
           (io.netty.handler.codec.http2
             DefaultHttp2Headers
             Http2Headers)
           (io.netty.util AsciiString)))

(def ^:private str= "AsciiString-aware equals" #(AsciiString/contentEquals %1 %2))

(deftest test-add-header
  (testing "add single header"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name "test-header"
          header-value "test-value"]
      (add-header h2-headers header-name header-value)
      (is (= header-value (.get h2-headers header-name)))))

  (testing "add multiple headers"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name "test-header"
          header-values ["value1" "value2"]]
      (add-header h2-headers header-name header-values)
      (is (= header-values (.getAll h2-headers header-name)))))

  (testing "throws on nil header name"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name nil
          header-value "test-value"]
      (is (thrown? Exception
                   (add-header h2-headers header-name header-value)))))

  (testing "throws on invalid HTTP/1 connection-related header"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name "Connection"
          header-value "test-value"]
      (is (thrown? Exception
                   (add-header h2-headers header-name header-value)))))

  (testing "throws on invalid pseudo-header"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name ":invalid-pseudo-header"
          header-value "test-value"]
      (is (thrown? Exception
                   (add-header h2-headers header-name header-value)))))

  (testing "throws on invalid 'transfer-encoding' header value"
    (let [h2-headers (DefaultHttp2Headers.)
          header-name "transfer-encoding"
          header-value "deflate"]
      (is (thrown? Exception
                   (add-header h2-headers header-name header-value))))))

(deftest test-parse-status
  (testing "parse numeric status"
    (let [status 200]
      (is (= (HttpResponseStatus/OK)
             (parse-status status)))))

  (testing "parse string status"
    (let [status "200"]
      (is (= (HttpResponseStatus/OK)
             (parse-status status)))))

  (testing "parse AsciiString status"
    (let [status (AsciiString. "200")]
      (is (= (HttpResponseStatus/OK)
             (parse-status status)))))

  (testing "parse HttpResponseStatus"
    (let [status (HttpResponseStatus/OK)]
      (is (= status
             (parse-status status)))))

  (testing "throws on unknown status class"
    (let [status :unknown]
      (is (thrown? IllegalArgumentException
                   (parse-status status))))))

(deftest test-ring-map->netty-http2-headers
  (testing "valid request map"
    (let [request {:request-method :get
                   :scheme :https
                   :authority "localhost"
                   :uri "/path"
                   :query-string "a=1"}
          headers (ring-map->netty-http2-headers request 1 true)]
      (is (str= "GET" (.method headers)))
      (is (str= "https" (.scheme headers)))
      (is (str= "localhost" (.authority headers)))
      (is (str= "/path?a=1" (.path headers)))))

  (testing "valid response map"
    (let [response {:status 200}
          headers (ring-map->netty-http2-headers response 1 false)]
      (is (str= "200" (.status headers)))))

  (testing "missing :request-method"
    (let [request {}]
      (is (thrown? Exception (ring-map->netty-http2-headers request 1 true)))))

  (testing "missing :scheme"
    (let [request {:request-method :get}]
      (is (thrown? Exception (ring-map->netty-http2-headers request 1 true)))))

  (testing "missing :authority"
    (let [request {:request-method :get
                   :scheme :https}]
      (is (thrown? Exception (ring-map->netty-http2-headers request 1 true)))))

  (testing "missing :uri and :query-string"
    (let [request {:request-method :get
                   :scheme :https
                   :authority "localhost"}]
      (is (thrown? Exception (ring-map->netty-http2-headers request 1 true)))))

  ;; For backwards-compatibility with Aleph HTTP1 code, we set to 200 if missing
  ;;(testing "missing :status"
  ;;  (let [response {}]
  ;;    (is (thrown? Exception (ring-map->netty-http2-headers response 1 false)))))

  (testing "headers added"
    (let [request {:request-method :get
                   :scheme :https
                   :authority "localhost"
                   :uri "/path"
                   :headers {"X-Test" "value"}}
          headers (ring-map->netty-http2-headers request 1 true)]
      (is (str= "value" (.get headers "x-test"))))))

;; really hate the conflation of "private" with "things I don't want to test"
(let [try-set-content-length! #'aleph.http.http2/try-set-content-length!]

  (deftest test-try-set-content-length!
    (testing "set content-length header"
      (let [headers (DefaultHttp2Headers.)
            length 100]
        (try-set-content-length! headers length)
        (is (= (str length) (.get headers "content-length")))))

    (testing "do not overwrite existing content-length header"
      (let [headers (DefaultHttp2Headers.)
            length 100]
        (.set headers "content-length" "200")
        (try-set-content-length! headers length)
        (is (= "200" (.get headers "content-length")))))

    (testing "do not set content-length for 1xx and 204 status codes"
      (doseq [status [100 150 199 204]]
        (let [headers (DefaultHttp2Headers.)
              length 100]
          (.status headers (str status))
          (try-set-content-length! headers length)
          (is (nil? (.get headers "content-length"))))))

    (testing "throw on negative length values"
      (let [headers (DefaultHttp2Headers.)
            length -100]
        (is (thrown? IllegalArgumentException (try-set-content-length! headers length)))))))

(let [validate-netty-req-headers #'aleph.http.http2/validate-netty-req-headers
      stream-id 1]

  (deftest test-validate-netty-req-headers
    (testing "all mandatory pseudo-headers present"
      (let [headers (DefaultHttp2Headers.)]
        (.method headers "GET")
        (.scheme headers "http")
        (.path headers "/")
        (is (= headers (validate-netty-req-headers headers stream-id)))))

    (testing "missing method pseudo-header"
      (let [headers (DefaultHttp2Headers.)]
        (.scheme headers "http")
        (.path headers "/")
        (is (thrown? Exception
                     (validate-netty-req-headers headers stream-id)))))

    (testing "missing scheme pseudo-header"
      (let [headers (DefaultHttp2Headers.)]
        (.method headers "GET")
        (.path headers "/")
        (is (thrown? Exception
                     (validate-netty-req-headers headers stream-id)))))

    (testing "missing path pseudo-header"
      (let [headers (DefaultHttp2Headers.)]
        (.method headers "GET")
        (.scheme headers "http")
        (is (thrown? Exception
                     (validate-netty-req-headers headers stream-id)))))))


(deftest test-HeaderMap-keys
  (let [^Http2Headers h2-headers (DefaultHttp2Headers.)
        _ (run! #(apply add-header h2-headers %)
                {"Accept"                "text/html"
                 "Authorization"         "Basic narfdorfle"
                 :Content                "text/test"})
        m (headers->map h2-headers)
        changed-map (-> m
                        (assoc "x-tra" "foobar")
                        (dissoc "authorization" "content"))]
    (is (= #{"accept" "x-tra"} (-> changed-map keys set)))))
