(ns aleph.http.client-middleware-test
  (:require [aleph.http.client-middleware :as middleware]
    [clojure.test :as t :refer [deftest is]]))

(deftest test-empty-query-string
  (is (= "" (middleware/generate-query-string {})))
  (is (= "" (middleware/generate-query-string {} "text/plain; charset=utf-8")))
  (is (= "" (middleware/generate-query-string {} "text/html;charset=ISO-8859-1"))))
