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
                                            "Content-type"           "application/json"
                                            "content-type"           "application/json"
        m (core/headers->map (.headers req))
        changed-map (-> m
                        (assoc "x-tra" "foobar")
                        (dissoc "authorization" "content"))]
    (is (= "application/json" (-> req .headers (.get "Content-Type")))
        "normalize will not allow duplicates for singleton keys, we get a single value out")
    (is (= #{"accept" "x-tra"} (-> changed-map keys set)))))
