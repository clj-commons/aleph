(ns aleph.http.multipart-test
  (:require
    [aleph.http :as http]
    [aleph.http.core :as core]
    [aleph.http.multipart :as mp]
    [clj-commons.byte-streams :as bs]
    [clojure.edn :as edn]
    [clojure.test :refer [deftest testing is]]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [clojure.string :as str])
  (:import
    (io.netty.buffer
      ByteBufAllocator)
    (io.netty.handler.codec.http
      HttpContent)
    (io.netty.handler.stream
      ChunkedInput)
    (java.io
      File)))

(def file-to-send (File. (str (System/getProperty "user.dir") "/test/file.txt")))

(deftest test-multipart-builder
  (let [body (mp/encode-body [{:part-name "part1"
                               :content "content1"
                               :charset "UTF-8"}
                              {:part-name "part2"
                               :content "content2"}
                              {:part-name "part3"
                               :content "content3"
                               :mime-type "application/json"}
                              {:part-name "part4"
                               :content "content4"
                               :mime-type "application/xml"
                               :charset "ISO-8859-1"}
                              {:part-name "part5"
                               :content "content5"
                               :name "content5.pdf"}
                              {:name "part6"
                               :content "content6"}])
        body-str (bs/to-string body)]
    (is (.contains body-str "name=\"part1\""))
    (is (.contains body-str "name=\"part2\""))
    (is (.contains body-str "name=\"part3\""))
    (is (.contains body-str "name=\"part4\""))
    (is (.contains body-str "name=\"part5\""))
    (is (.contains body-str "name=\"part6\""))
    (is (.contains body-str "content1"))
    (is (.contains body-str "content2"))
    (is (.contains body-str "Content-Disposition: form-data;"))
    ;; default mime-type
    (is (.contains body-str "Content-Type: application/octet-stream; charset=UTF-8"))
    ;; omitting charset
    (is (.contains body-str "Content-Type: application/json\r\n"))
    ;; mime-type + charset
    (is (.contains body-str "Content-Type: application/xml; charset=ISO-8859-1"))
    ;; filename header
    (is (.contains body-str "filename=\"content5.pdf\""))))

(deftest test-custom-boundary
  (let [b (mp/boundary)
        body (mp/encode-body b [{:part-name "part1" :content "content1"}])
        body-str (bs/to-string body)]
    (is (.endsWith body-str (str b "--")))))

(deftest test-base64-content-transfer-encoding
  (let [body (mp/encode-body [{:part-name "part1"
                               :content "content1"
                               :transfer-encoding :base64}])
        body-str (bs/to-string body)]
    (is (.contains body-str "base64"))
    (is (.contains body-str "Y29udGVudDE="))))

(deftest test-binary-content-transfer-encoding
  (testing "specify 'binary' in headers"
    (let [body (mp/encode-body [{:part-name "part1"
                                 :content "content1"
                                 :transfer-encoding :binary}])
          body-str (bs/to-string body)]
      (is (.contains body-str "content1"))
      (is (.contains body-str "Content-Transfer-Encoding: binary"))))
  (testing "omits content-transfer-encoding for nil"
    (let [body (mp/encode-body [{:part-name "part2"
                                 :content "content2"
                                 :transfer-encoding nil}])
          body-str (bs/to-string body)]
      (is (.contains body-str "content2"))
      (is (false? (.contains body-str "Content-Transfer-Encoding"))))))

(deftest reject-unknown-transfer-encoding
  (is (thrown? IllegalArgumentException
      (mp/encode-body [{:part-name "part1"
                        :content "content1"
                        :transfer-encoding :uknown-transfer-encoding}]))))

(deftest test-content-as-file
  (let [parts [{:part-name "part1"
                :content file-to-send}
               {:part-name "part2"
                :mime-type "application/png"
                :content file-to-send}
               {:part-name "part3"
                :name "text-file-to-send.txt"
                :content file-to-send}
               {:part-name "part4"
                :charset "UTF-8"
                :content file-to-send}
               {:content file-to-send}
               {:content file-to-send
                :transfer-encoding :base64}]
        validate (fn [^String body-str]
                   (is (.contains body-str "name=\"part1\""))
                   (is (.contains body-str "name=\"part2\""))
                   (is (.contains body-str "name=\"part3\""))
                   (is (.contains body-str "name=\"part4\""))
                   (is (.contains body-str "name=\"file.txt\""))
                   (is (.contains body-str "filename=\"file.txt\""))
                   (is (.contains body-str "filename=\"file.txt\""))
                   (is (.contains (str/lower-case body-str) (str/lower-case "Content-Type: text/plain\r\n")))
                   (is (.contains (str/lower-case body-str) (str/lower-case "Content-Type: text/plain; charset=UTF-8\r\n")))
                   (is (.contains (str/lower-case body-str) (str/lower-case "Content-Type: application/png\r\n"))))]
    (testing "legacy encode-body"
      (let [body (mp/encode-body parts)
            body-str (bs/to-string body)]
        (validate body-str)
        (is (.contains body-str "Content-Transfer-Encoding: base64\r\n"))))

    (testing "encode-request"
      (let [req (core/ring-request->netty-request {:request-method :get})
            [_ body] (mp/encode-request req parts)
            body-str (-> ^ChunkedInput body ^HttpContent (.readChunk ByteBufAllocator/DEFAULT) .content bs/to-string)]
        (validate body-str)
        (is (.contains body-str "content-transfer-encoding: binary\r\n"))))))

(def port1 26023)
(def port2 26024)
(def port3 26025)
(def url1 (str "http://localhost:" port1))
(def url2 (str "http://localhost:" port2))
(def url3 (str "http://localhost:" port3))

(def parts [{:part-name "#0-string"
             :content "CONTENT1"}
            {:part-name "#1-bytes"
             :content (.getBytes "CONTENT2" "UTF-8")}
            {:part-name "#2-file"
             :content file-to-send}
            {:part-name "#3-file-with-mime-type"
             :mime-type "application/png"
             :content file-to-send}
            {:part-name "#4-file-with-name"
             :name "text-file-to-send.txt"
             :content file-to-send}
            {:part-name "#5-file-with-charset"
             :content file-to-send
             :charset "ISO-8859-1"}])

(defn echo-handler [{:keys [body]}]
  {:status 200
   :body body})

(deftest test-send-multipart-request
  (let [s (http/start-server echo-handler {:port port1})
        ^String resp @(d/chain'
                       (http/post url1 {:multipart parts})
                       :body
                       bs/to-string)]
    ;; part names
    (doseq [{:keys [part-name]} parts]
      (is (.contains resp (str "name=\"" part-name "\""))))

    ;; contents from a string, bytes, files
    (is (.contains resp "CONTENT1"))
    (is (.contains resp "CONTENT2"))
    (is (.contains resp "this is a file"))

    ;; mime types: set explicitly and automatically derived
    (is (.contains resp "content-type: text/plain"))
    (is (.contains resp "content-type: application/png"))
    (is (.contains resp "; charset=UTF-8"))
    (is (.contains resp "; charset=ISO-8859-1"))

    ;; explicit filename
    (is (.contains resp "filename=\"file.txt\""))

    (.close ^java.io.Closeable s)))

(defn- pack-chunk [{:keys [content] :as chunk}]
  (cond-> (dissoc chunk :file)
    (not (string? content))
    (dissoc :content)))

(defn- decode-handler [req]
  (let [chunks (-> req
                   mp/decode-request
                   s/stream->seq)]
    {:status 200
     :body (pr-str (map pack-chunk chunks))}))

(defn- test-decoder [port url raw-stream?]
  (let [s (http/start-server decode-handler {:port port
                                             :raw-stream? raw-stream?})
        chunks (-> (http/post url {:multipart parts})
                   (deref 1e3 {:body "timeout"})
                   :body
                   bs/to-string
                   clojure.edn/read-string
                   vec)]
    (is (= 6 (count chunks)))

    ;; part-names
    (is (= (map :part-name parts)
           (map :part-name chunks)))

    ;; content
    (is (= "CONTENT1" (get-in chunks [0 :content])))

    ;; mime type
    (is (= "text/plain" (get-in chunks [2 :mime-type])))
    (is (= "application/png" (get-in chunks [3 :mime-type])))

    ;; filename
    (is (= "file.txt" (get-in chunks [3 :name])))
    (is (= "file.txt" (get-in chunks [4 :name])))

    ;; charset
    (is (= "ISO-8859-1" (get-in chunks [5 :charset])))

    (.close ^java.io.Closeable s)))

(deftest test-mutlipart-request-decode-with-ring-handler
  (test-decoder port2 url2 false))

(deftest test-mutlipart-request-decode-with-raw-handler
  (test-decoder port3 url3 true))
