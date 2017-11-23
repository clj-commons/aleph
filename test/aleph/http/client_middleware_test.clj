(ns aleph.http.client-middleware-test
  (:require [aleph.http.client-middleware :as middleware]
            [clojure.test :as t :refer [deftest is]])
  (:import java.net.URLDecoder))

(deftest test-empty-query-string
  (is (= "" (middleware/generate-query-string {})))
  (is (= "" (middleware/generate-query-string {} "text/plain; charset=utf-8")))
  (is (= "" (middleware/generate-query-string {} "text/html;charset=ISO-8859-1"))))

(deftest test-basic-auth-value-encoding
  ;; see https://tools.ietf.org/html/rfc2617#page-5
  (is (= "Basic Og==" (middleware/basic-auth-value nil)))
  (is (= "Basic Og==" (middleware/basic-auth-value [])))
  (is (= "Basic " (middleware/basic-auth-value "")))
  (is (= "Basic dXNlcm5hbWU6cGFzc3dvcmQ=" (middleware/basic-auth-value "username:password")))
  (is (= "Basic dXNlcm5hbWU6cGFzc3dvcmQ=" (middleware/basic-auth-value ["username" "password"])))
  (is (= "Basic dXNlcm5hbWU6" (middleware/basic-auth-value ["username"])))
  (is (= "Basic dXNlcm5hbWU=" (middleware/basic-auth-value "username"))))

(deftest test-coerce-form-params
  (is (= "{\"foo\":\"bar\"}" (middleware/coerce-form-params {:content-type :json
                                                             :form-params {:foo :bar}})))
  (is (= "[\"^ \",\"~:foo\",\"~:bar\"]" (slurp (middleware/coerce-form-params {:content-type :transit+json
                                                                               :form-params {:foo :bar}}))))
  (is (= "{:foo :bar}" (middleware/coerce-form-params {:content-type :edn
                                                       :form-params {:foo :bar}})))
  (is (= "foo=%3Abar" (middleware/coerce-form-params {:content-type :default
                                                      :form-params {:foo :bar}})))
  (is (= (middleware/coerce-form-params {:content-type :default
                                         :form-params {:foo :bar}})
        (middleware/coerce-form-params {:form-params {:foo :bar}}))))

(deftest test-nested-query-params
  (let [req {:query-params {:foo {:bar "baz"}}}
        {:keys [query-string]} (reduce #(%2 %1) req middleware/default-middleware)]
    (is (= "foo[bar]=baz" (URLDecoder/decode query-string)))))
