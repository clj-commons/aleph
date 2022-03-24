(ns aleph.http.core-test
  (:use
    [clojure test])
  (:require
    [aleph.http.core :as core])
  (:import
    [io.netty.handler.codec.http
     DefaultHttpRequest DefaultHttpResponse]))

(deftest test-HeaderMap-keys
  (let [^DefaultHttpRequest req (core/ring-request->netty-request
                                  {:uri "http://example.com"
                                   :request-method "get"
                                   :headers {"Accept" "text/html"
                                             "Authorization" "Basic narfdorfle"}})
        map (core/headers->map (.headers req))
        dissoc-map (dissoc map "authorization")]
    (is (= #{"accept"}  (-> dissoc-map keys set)))))

(deftest test-map->headerkeys
  (let [ring-response {:status 200
                       :headers {"Content-Type" "text/html"
                                 "etag" "c561c68d0ba92bbeb8b0f612a9199f722e3a621a"
                                 "x-amz-meta-key" "foo"}}
        ^DefaultHttpResponse resp (core/ring-response->netty-response ring-response)
        ^DefaultHttpResponse resp-hkt (core/ring-response->netty-response ring-response identity)]
    (is (= #{"ETag" "X-Amz-Meta-Key" "Content-Type"} (-> resp .headers keys set)))
    (is (= #{"ETag" "x-amz-meta-key" "Content-Type"} (-> resp-hkt .headers keys set)))))
