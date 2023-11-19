(ns aleph.http.core-test
  (:require
    [aleph.http.core :as core]
    [clojure.test :refer [deftest is]])
  (:import
    (io.netty.handler.codec.http DefaultHttpRequest)))

(deftest test-HeaderMap-keys
  (let [^DefaultHttpRequest req (core/ring-request->netty-request
                                  {:uri "http://example.com"
                                   :request-method "get"
                                   :headers {"Accept"                "text/html"
                                             "Authorization"         "Basic narfdorfle"
                                             :Content                "text/test"}})
        m (core/headers->map (.headers req))
        changed-map (-> m
                        (assoc "x-tra" "foobar")
                        (dissoc "authorization" "content"))]
    (is (= #{"accept" "x-tra"} (-> changed-map keys set)))))
