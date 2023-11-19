(ns aleph.http.common-test
  (:require [aleph.http.common :as common]
            [clojure.string    :as str]
            [clojure.test      :refer [deftest is]]))

(deftest test-invalid-value-exception
  (is (str/starts-with?
       (ex-message (common/invalid-value-exception nil nil))
       "Cannot treat nil as a response to '{}'."))
  (is (str/starts-with?
       (ex-message (common/invalid-value-exception nil false))
       "Cannot treat false of class java.lang.Boolean as a response to '{}'."))
  (is (str/starts-with?
       (ex-message (common/invalid-value-exception {:uri "http://localhost/hello"
                                                    :request-method :get}
                                                   nil))
       "Cannot treat nil as a response to '{:uri \"http://localhost/hello\", :request-method :get}'."))
  (is (str/starts-with?
       (ex-message (common/invalid-value-exception {:uri "http://localhost/hello"
                                                    :request-method :get}
                                                   "hello"))
       "Cannot treat \"hello\" of class java.lang.String as a response to '{:uri \"http://localhost/hello\", :request-method :get}'.")))
