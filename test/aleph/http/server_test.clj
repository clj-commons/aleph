(ns aleph.http.server-test
  (:require [aleph.http.server :as server]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest test-invalid-value-exception
  (is (str/starts-with?
       (ex-message (server/invalid-value-exception nil nil))
       "Cannot treat nil of null as a response to '{}'.\nRing response expected."))
  (is (str/starts-with?
       (ex-message (server/invalid-value-exception {:uri "http://localhost/hello"
                                                    :request-method :get} nil))
       "Cannot treat nil of null as a response to '{:uri \"http://localhost/hello\", :request-method :get}'.\nRing response expected."))
  (is (str/starts-with?
       (ex-message (server/invalid-value-exception {:uri "http://localhost/hello"
                                                    :request-method :get} "hello"))
       "Cannot treat \"hello\" of class java.lang.String as a response to '{:uri \"http://localhost/hello\", :request-method :get}'.\nRing response expected.")))
