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
                                   :headers {"Accept" "text/html"
                                             "Authorization" "Basic narfdorfle"}})
        map (core/headers->map (.headers req))
        dissoc-map (dissoc map "authorization")]
    (is (= #{"accept"}  (-> dissoc-map keys set)))))
