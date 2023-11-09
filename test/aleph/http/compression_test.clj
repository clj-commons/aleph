(ns aleph.http.compression-test
  (:require [aleph.http.compression :refer :all]
            [aleph.testutils :refer [str=]]
            [clojure.test :refer :all]))


(deftest target-encodings-test
  (let [accept->content {""             nil
                         ","            nil
                         "identity"     nil
                         "unknown"      nil
                         "*"            "br"
                         "br"           "br"
                         "br ; q=0.1"   "br"
                         "unknown, br"  "br"
                         "br, gzip"     "br"
                         "gzip, br"     "br"
                         "identity, br" "br"
                         "gzip"         "gzip"
                         "gzip ; q=0.1" "gzip"}]
    (doseq [[accept content] accept->content]
      (is (str= content (->> accept
                             parse-accept-encoding
                             (choose-codec available-compressor-options)))
          (str "Accept-Encoding \""
               accept
               "\" should have chosen "
               (if (some? content)
                 (str "\"" content "\"")
                 "nil"))))))
