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
  (is (= "[\"^ \",\"~:foo\",\"~:bar\"]"
         (slurp (middleware/coerce-form-params {:content-type :transit+json
                                                :form-params {:foo :bar}}))))
  (is (= "{:foo :bar}" (middleware/coerce-form-params {:content-type :edn
                                                       :form-params {:foo :bar}})))
  (is (= "foo=%3Abar" (middleware/coerce-form-params {:content-type :default
                                                      :form-params {:foo :bar}})))
  (is (= "foo=%3Abar&foo=%3Abaz"
         (middleware/coerce-form-params {:content-type :default
                                         :form-params {:foo [:bar :baz]}})))
  (is (= "foo[]=%3Abar&foo[]=%3Abaz"
         (middleware/coerce-form-params {:content-type :default
                                         :multi-param-style :array
                                         :form-params {:foo [:bar :baz]}})))
  (is (= "foo[0]=%3Abar&foo[1]=%3Abaz"
         (middleware/coerce-form-params {:content-type :default
                                         :multi-param-style :indexed
                                         :form-params {:foo [:bar :baz]}})))
  (is (= (middleware/coerce-form-params {:content-type :default
                                         :form-params {:foo :bar}})
        (middleware/coerce-form-params {:form-params {:foo :bar}}))))

(defn req->query-string [req]
  (-> (reduce #(%2 %1) req middleware/default-middleware)
      :query-string
      URLDecoder/decode))

(defn req->body-raw [req]
  (:body (reduce #(%2 %1) req middleware/default-middleware)))

(defn req->body-decoded [req]
  (URLDecoder/decode (req->body-raw req)))

(deftest test-nested-params
  (is (= "foo[bar]=baz" (req->query-string {:query-params {:foo {:bar "baz"}}})))
  (is (= "foo[bar]=baz" (req->query-string {:query-params {:foo {:bar "baz"}}
                                            :content-type :json})))
  (is (= "foo[bar]=baz" (req->query-string {:query-params {:foo {:bar "baz"}}
                                            :ignore-nested-query-string false})))
  (is (= "foo={:bar \"baz\"}" (req->query-string {:query-params {:foo {:bar "baz"}}
                                                  :ignore-nested-query-string true})))
  (is (= "foo[bar]=baz" (req->body-decoded {:method :post
                                            :form-params {:foo {:bar "baz"}}})))
  (is (= "foo[bar]=baz" (req->body-decoded {:method :post
                                            :flatten-nested-form-params true
                                            :form-params {:foo {:bar "baz"}}})))
  (is (= "foo={:bar \"baz\"}" (req->body-decoded {:method :post
                                                  :flatten-nested-form-params false
                                                  :form-params {:foo {:bar "baz"}}})))
  (is (= "{\"foo\":{\"bar\":\"baz\"}}"
         (req->body-raw {:method :post
                         :content-type :json
                         :form-params {:foo {:bar "baz"}}}))))

(deftest test-query-string-multi-param
  (is (= "name=John" (middleware/generate-query-string {:name "John"})))
  (is (= "name=John&name=Mark" (middleware/generate-query-string {:name ["John" "Mark"]})))
  (is (= "name=John&name=Mark"
         (middleware/generate-query-string {:name ["John" "Mark"]} nil :default)))
  (is (= "name[]=John&name[]=Mark"
         (middleware/generate-query-string {:name ["John" "Mark"]} nil :array)))
  (is (= "name[0]=John&name[1]=Mark"
         (middleware/generate-query-string {:name ["John" "Mark"]} nil :indexed))))
