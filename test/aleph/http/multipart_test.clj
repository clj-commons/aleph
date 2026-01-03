(ns aleph.http.multipart-test
  (:require
   [aleph.http :as http]
   [aleph.http.core :as core]
   [aleph.http.multipart :as mp]
   [aleph.netty :as netty]
   [aleph.testutils]
   [clj-commons.byte-streams :as bs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as d]
   [manifold.stream :as s])
  (:import
   (io.netty.buffer ByteBufAllocator)
   (io.netty.handler.codec.http HttpContent)
   (io.netty.handler.stream ChunkedInput)
   (java.io File)))

(def file-to-send (File. (str (System/getProperty "user.dir") "/test/file.txt")))

#_{:clj-kondo/ignore [:deprecated-var]}
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

#_{:clj-kondo/ignore [:deprecated-var]}
(deftest test-custom-boundary
  (let [b (mp/boundary)
        body (mp/encode-body b [{:part-name "part1" :content "content1"}])
        body-str (bs/to-string body)]
    (is (.endsWith body-str (str b "--")))))

#_{:clj-kondo/ignore [:deprecated-var]}
(deftest test-base64-content-transfer-encoding
  (let [body (mp/encode-body [{:part-name "part1"
                               :content "content1"
                               :transfer-encoding :base64}])
        body-str (bs/to-string body)]
    (is (.contains body-str "base64"))
    (is (.contains body-str "Y29udGVudDE="))))

#_{:clj-kondo/ignore [:deprecated-var]}
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

#_{:clj-kondo/ignore [:deprecated-var]}
(deftest reject-unknown-transfer-encoding
  (is (thrown? IllegalArgumentException
               (mp/encode-body [{:part-name "part1"
                                 :content "content1"
                                 :transfer-encoding :uknown-transfer-encoding}]))))

#_{:clj-kondo/ignore [:deprecated-var]}
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
             :charset "ISO-8859-1"}
            {:part-name "#6-bytes-with-mime-type"
             :mime-type "text/plain"
             :content (.getBytes "CONTENT3" "UTF-8")}
            {:part-name "#7-bytes-with-file-name"
             :file-name "data.txt"
             :content (.getBytes "CONTENT4" "UTF-8")}])

(defn echo-handler [{:keys [body]}]
  {:status 200
   :body body})

(deftest test-send-multipart-request
  (let [s (http/start-server echo-handler {:port port1 :shutdown-timeout 0})
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

(defn- pack-part [{:keys [content file] :as part}]
  (cond-> part
    (some? file)
    (assoc :file (.getAbsolutePath ^java.io.File file))
    (some? content)
    (update :content bs/to-string)))

(defn- make-decode-handler [options]
  (fn [req]
    (let [body   (:body req)
          data   (bs/to-string body)
          parts  (-> (assoc req :body (netty/to-byte-buf-stream (.getBytes data) 512))
                     (#(mp/decode-request % options))
                     s/stream->seq)
          packed-parts (mapv (fn [{:keys [file] :as part}]
                               (Thread/sleep 20) ;; wait a bit to ensure the files are cleaned when using automatic resource cleanup
                               (when file
                                 (is (instance? java.io.File file))
                                 (is (not (.exists ^java.io.File file)))) ;; demonstrates there is an issue when the stream is closed before accessing the file.
                               (pack-part part))
                             parts)]
      {:status 200
       :body (pr-str {:parts packed-parts
                      :encoded-data data})})))

(defn- make-decode-manual-handler [options]
  (fn [req]
    (let [body   (:body req)
          data   (bs/to-string body)
          [parts cleanup-fn]  (-> (assoc req :body (netty/to-byte-buf-stream (.getBytes data) 512))
                                  (#(mp/decode-request % options)))
          packed-parts (mapv (fn [{:keys [file] :as part}]
                               (Thread/sleep 20) ;; wait a bit to ensure the files are cleaned when using automatic resource cleanup
                               (when file
                                 (is (some? (slurp (.getAbsolutePath ^java.io.File file)))))
                               (pack-part part))
                             (s/stream->seq parts))]
      (is (every? #(.exists (io/file %)) (keep :file packed-parts)))
      (cleanup-fn)
      (is (every? #(not (.exists (io/file %))) (keep :file packed-parts)))
      {:status 200
       :body (pr-str {:parts packed-parts
                      :encoded-data data})})))

(defn- test-decoder
  ([port url handler]
   (test-decoder port url handler {}))
  ([port url handler options]
   (let [handler (bound-fn* handler)
         s (http/start-server handler (merge {:port port
                                              :shutdown-timeout 0}
                                             options))]

     (try
       (let [req (http/post url {:multipart parts})
             resp         (deref req 1e3 {:body "timeout"})
             body         (-> (:body resp) bs/to-string read-string)
             encoded-data (:encoded-data body)
             parts-resp   (-> body :parts vec)]
         (is (= 8 (count parts-resp)))

         ;; part-names
         (is (= (map :part-name parts)
                (map :part-name parts-resp)))

         ;; content
         (is (= "CONTENT1" (get-in parts-resp [0 :content])))

         ;; mime type
         (is (= "text/plain" (get-in parts-resp [2 :mime-type])))
         (is (= "application/png" (get-in parts-resp [3 :mime-type])))

         ;; filename
         (is (= "file.txt" (get-in parts-resp [3 :name])))
         (is (= "file.txt" (get-in parts-resp [4 :name])))

         ;; charset
         (is (= "ISO-8859-1" (get-in parts-resp [5 :charset])))

         ;; mime-type + memory data
         (is (re-find #"content-disposition: form-data; name=\"#6-bytes-with-mime-type\"; filename=\".*\"\r\ncontent-length: 8\r\ncontent-type: text/plain\r\ncontent-transfer-encoding: binary\r\n\r\nCONTENT3\r\n" encoded-data))
         (is (uuid? (parse-uuid (get-in parts-resp [6 :name]))))
         (is (= "text/plain" (get-in parts-resp [6 :mime-type])))

         (is (re-find #"content-disposition: form-data; name=\"#7-bytes-with-file-name\"; filename=\"data\.txt\"\r\ncontent-length: 8\r\ncontent-type: application/octet-stream\r\ncontent-transfer-encoding: binary\r\n\r\nCONTENT4\r\n" encoded-data))
         (is (= "data.txt" (get-in parts-resp [7 :name])))
         (is (= "application/octet-stream" (get-in parts-resp [7 :mime-type]))))

       (finally (.close ^java.io.Closeable s))))))

(deftest test-multipart-request-decode-with-ring-handler
  (testing "automatic cleanup"
    (testing "without memory-limit"
      (test-decoder port2 url2 (make-decode-handler {})))
    (testing "with infinite memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:memory-limit Long/MAX_VALUE})))
    (testing "with small memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:memory-limit 12})))
    (testing "with zero memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:memory-limit 0}))))

  (testing "manual cleanup"
    (testing "without memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:manual-cleanup? true})))
    (testing "with infinite memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:memory-limit Long/MAX_VALUE
                                                            :manual-cleanup? true})))
    (testing "with small memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:memory-limit 12
                                                            :manual-cleanup? true})))
    (testing "with zero memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:memory-limit 0
                                                            :manual-cleanup? true})))))

(deftest test-multipart-request-decode-with-raw-handler
  (testing "automatic cleanup"
    (testing "without memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:raw-stream? true})))
    (testing "with infinite memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:raw-stream? true
                                                     :memory-limit Long/MAX_VALUE})))
    (testing "with small memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:raw-stream? true
                                                     :memory-limit 12})))
    (testing "with zero memory-limit"
      (test-decoder port2 url2 (make-decode-handler {:raw-stream? true
                                                     :memory-limit 0}))))

  (testing "manual cleanup"
    (testing "without memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:raw-stream? true
                                                            :manual-cleanup? true})))
    (testing "with infinite memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:raw-stream? true
                                                            :memory-limit Long/MAX_VALUE
                                                            :manual-cleanup? true})))
    (testing "with small memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:raw-stream? true
                                                            :memory-limit 12
                                                            :manual-cleanup? true})))
    (testing "with zero memory-limit"
      (test-decoder port2 url2 (make-decode-manual-handler {:raw-stream? true
                                                            :memory-limit 0
                                                            :manual-cleanup? true})))))

(aleph.testutils/instrument-tests-with-dropped-error-deferred-detection!)
