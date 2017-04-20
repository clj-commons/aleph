(ns aleph.http.multipart-test
  (:use
   [clojure test])
  (:require
   [aleph.http.multipart :as mp]
   [byte-streams :as bs]))

(deftest test-multipart-builder
  (let [body (mp/encode-body [{:name "part1"
                               :content "content1"
                               :charset "UTF-8"}
                              {:name "part2"
                               :content "content2"
                               :charset "ascii"
                               :mime-type "application/json"
                               :filename "content2.pdf"}])
        body-str (bs/to-string body)]
    (is (.contains body-str "name=\"part1\""))
    (is (.contains body-str "name=\"part2\""))
    (is (.contains body-str "content1"))
    (is (.contains body-str "content2"))
    (is (.contains body-str "content-disposition: form-data;"))
    (is (.contains body-str "content-type: application/octet-stream;charset=UTF-8"))
    (is (.contains body-str "content-type: application/json;charset=ascii"))
    (is (.contains body-str "filename=\"content2.pdf\""))))

(deftest test-custom-boundary
  (let [b (mp/boundary)
        body (mp/encode-body b [{:name "part1" :content "content1"}])
        body-str (bs/to-string body)]
    (is (.endsWith body-str (str b "--")))))
