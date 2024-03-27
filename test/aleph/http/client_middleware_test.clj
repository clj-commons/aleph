(ns aleph.http.client-middleware-test
  (:require
   [aleph.http.client-middleware :as middleware]
   [aleph.http.schema :as schema]
   [clojure.test :as t :refer [deftest is]]
   [malli.core :as m]
   [malli.generator :as mg])
  (:import
   (java.net URLDecoder)))

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

(deftest test-nested-query-params
  (let [req {:request-method :get
             :query-params {:foo {:bar "baz"}}}
        {:keys [query-string]} (reduce #(%2 %1) req middleware/default-middleware)]
    (is (= "foo[bar]=baz" (URLDecoder/decode query-string "UTF-8")))))

(deftest test-cookie-store
  (let [c1 {:name "id"
            :value "42"
            :domain "domain.com"
            :path "/"
            :max-age nil
            :http-only? true
            :secure? false}
        c2 {:name "track"
            :value "off"
            :domain "domain.com"
            :path "/"
            :max-age nil
            :http-only? true
            :secure? true}
        c3 {:name "track"
            :value "on"
            :domain "domain.com"
            :path "/blog"
            :max-age nil
            :http-only? true
            :secure? true}
        c4 {:name "subdomain"
            :value "detect"
            :domain "subdomain.com"}
        c5 (middleware/decode-set-cookie-header "outdated=val; Domain=outdomain.com; Expires=Wed, 21 Oct 2015 07:28:00 GMT")
        cs (middleware/in-memory-cookie-store [c1 c2 c3 c4 c5])
        spec middleware/default-cookie-spec
        dc (middleware/decode-set-cookie-header "id=42; Domain=domain.com; Path=/; HttpOnly")]
    (is (= c1 dc))
    (is (= "id=42; track=off" (-> (middleware/add-cookie-header cs spec {:url "https://domain.com/"})
                                (get-in  [:headers "cookie"])))
      "emit cookie for /blog path")
    (is (= "id=42" (-> (middleware/add-cookie-header cs spec {:url "http://domain.com/"})
                     (get-in  [:headers "cookie"])))
      "http request should not set secure cookies")
    (is (= "id=42; track=on" (-> (middleware/add-cookie-header cs spec {:url "https://domain.com/blog"})
                               (get-in  [:headers "cookie"])))
      "the most specific path")
    (is (= "subdomain=detect" (-> (middleware/add-cookie-header cs spec {:url "https://www.subdomain.com"})
                                (get-in [:headers "cookie"])))
      "subdomain should match w/o leading dot under latest specifications")
    (is (nil? (-> (middleware/add-cookie-header cs spec {:url "https://anotherdomain.com/"})
                (get-in  [:headers "cookie"])))
      "domain mistmatch")
    (is (nil? (-> (middleware/add-cookie-header cs spec {:url "https://outdomain.com/"})
                (get-in [:headers "cookie"])))
      "should not set expired")
    (is (= "no_override"
          (-> (middleware/wrap-cookies {:cookie-store cs
                                        :cookie-spec spec
                                        :url "https://domain.com/"
                                        :headers {"cookie" "no_override"}})
            (get-in  [:headers "cookie"])))
      "no attempts to override when header is already set")
    (is (= "id=44" (-> (middleware/wrap-cookies {:url "https://domain.com/"
                                                 :cookies [{:name "id"
                                                            :value "44"
                                                            :domain "domain.com"}]})
                     (get-in [:headers "cookie"])))
      "accept cookies from req directly")
    (is (= "name=John" (-> (middleware/wrap-cookies {:url "https://domain.com/"
                                                     :cookies [{:name "name" :value "John"}]
                                                     :cookie-store cs})
                         (get-in [:headers "cookie"])))
      "explicitly set cookies override cookie-store even when specified")
    (is (nil? (middleware/decode-set-cookie-header ""))
        "empty set-cookie header doesn't crash")))

(defn req->query-string [req]
  (-> (reduce #(%2 %1) req middleware/default-middleware)
      :query-string
      (URLDecoder/decode "UTF-8")))

(defn req->body-raw [req]
  (:body (reduce #(%2 %1) req middleware/default-middleware)))

(defn req->body-decoded [req]
  (URLDecoder/decode (req->body-raw req) "UTF-8"))

(deftest test-nested-params
  (is (= "foo[bar]=baz" (req->query-string {:request-method :get
                                            :query-params {:foo {:bar "baz"}}})))
  (is (= "foo[bar]=baz" (req->query-string {:request-method :get
                                            :query-params {:foo {:bar "baz"}}
                                            :content-type :json})))
  (is (= "foo[bar]=baz" (req->query-string {:request-method :get
                                            :query-params {:foo {:bar "baz"}}
                                            :ignore-nested-query-string false})))
  (is (= "foo={:bar \"baz\"}" (req->query-string {:request-method :get
                                                  :query-params {:foo {:bar "baz"}}
                                                  :ignore-nested-query-string true})))
  (is (= "foo[bar]=baz" (req->body-decoded {:method :post
                                            :form-params {:foo {:bar "baz"}}})))
  (is (= "foo[bar]=baz" (req->body-decoded {:method :post
                                            :flatten-nested-form-params true
                                            :form-params {:foo {:bar "baz"}}})))
  (is (= "foo={:bar \"baz\"}" (req->body-decoded {:method :post
                                                  :flatten-nested-form-params false
                                                  :form-params {:foo {:bar "baz"}}})))
  (is (= "foo={:bar \"baz\"}" (req->body-decoded {:method :post
                                                  :flatten-nested-keys []
                                                  :form-params {:foo {:bar "baz"}}})))
  (is (= "foo={:bar \"baz\"}" (req->body-decoded {:method :post
                                                  :flatten-nested-keys [:query-params]
                                                  :form-params {:foo {:bar "baz"}}})))
  (is (= "foo[bar]=baz" (req->body-decoded {:method :post
                                            :flatten-nested-keys [:form-params]
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


(deftest test-wrap-validation
  (doseq [req (mg/sample schema/ring-request)]
    (is (middleware/wrap-validation req)))

  (testing "Request methods can be strings"
    (is (middleware/wrap-validation {:remote-addr    "localhost"
                                     :server-name    "computer"
                                     :scheme         :http
                                     :request-method "GET"})))

  (is (thrown-with-msg?
        IllegalArgumentException
        #"Invalid spec.*:in \[:request-method\].*:type :malli.core/missing-key"
        (middleware/wrap-validation {})))
  (is (thrown-with-msg?
        IllegalArgumentException
        #"Invalid spec.*:in \[:content-length\].*:value \"10\""
        (middleware/wrap-validation {:request-method :post
                                     :content-length "10"})))
  (is (thrown-with-msg?
        IllegalArgumentException
        #"Invalid spec.*:in \[:content-type\].*:value 10"
        (middleware/wrap-validation {:request-method :post
                                     :content-type 10}))))
