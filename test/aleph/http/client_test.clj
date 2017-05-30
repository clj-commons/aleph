(ns aleph.http.client-test
  (:require [aleph.http.client :as client]
            [clojure.test :as t :refer [deftest is testing]]))

(deftest test-domain-extracting
  (testing "ASCII domain is extracted from URL as is."
    (let [uri (client/req->domain {:url "https://domainname.com:80"})]
      (is (= "https" (.getScheme uri)))
      (is (= "domainname.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "ASCII domain is extracted from request host as is."
    (let [uri (client/req->domain {:scheme "https"
                                   :host "domainname.com"
                                   :server-name "someotherdomainname.com"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "domainname.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "ASCII domain is extracted from request server name as is."
    (let [uri (client/req->domain {:scheme "https"
                                   :server-name "domainname.com"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "domainname.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN in URL is translated to ASCII."
    (let [uri (client/req->domain {:url "https://名がドメイン.com:80"})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN in request host is translated to ASCII."
    (let [uri (client/req->domain {:scheme "https"
                                   :host "名がドメイン.com"
                                   :server-name "domainname.com"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN in request server name is translated to ASCII."
    (let [uri (client/req->domain {:scheme "https"
                                   :server-name "名がドメイン.com"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.com" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN + Internationalized TLD in URL is translated to ASCII."
    (let [uri (client/req->domain {:url "https://名がドメイン.укр:80"})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.xn--j1amh" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN + Internationalized TLD in request host is translated to ASCII."
    (let [uri (client/req->domain {:scheme "https"
                                   :host "名がドメイン.укр"
                                   :server-name "domainname.com"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.xn--j1amh" (.getHost uri)))
      (is (= 80 (.getPort uri)))))
  (testing "IDN + Internationalized TLD in request server name is translated to ASCII."
    (let [uri (client/req->domain {:scheme "https"
                                   :server-name "名がドメイン.укр"
                                   :port 80})]
      (is (= "https" (.getScheme uri)))
      (is (= "xn--v8jxj3d1dzdz08w.xn--j1amh" (.getHost uri)))
      (is (= 80 (.getPort uri))))))
