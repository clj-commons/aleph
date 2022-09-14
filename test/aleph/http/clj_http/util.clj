(ns aleph.http.clj-http.util
  (:require
    [aleph.http :as http]
    [clj-commons.byte-streams :as bs]
    [clj-http.core :as core]
    [clojure.set :as set]
    [clojure.test :refer :all])
  (:import
    (java.io ByteArrayInputStream
             ByteArrayOutputStream
             FilterInputStream
             InputStream)))

;; turn off default middleware for the core tests
(def no-middleware-pool (http/connection-pool {:middleware identity}))

(def base-req
  {:scheme :http
   :server-name "localhost"
   :server-port 18080})

(def uninteresting-headers ["date" "connection" "server"])

(defn header-keys
  "Returns a set of headers of interest"
  [m]
  (-> (apply dissoc m uninteresting-headers)
      (keys)
      (set)))

(defn is-headers=
  "Are the two header maps equal?

   Additional Aleph headers are ignored"
  [clj-http-headers aleph-headers]
  (let [clj-http-ks (header-keys clj-http-headers)
        aleph-ks (header-keys aleph-headers)]
    (is (set/superset? aleph-ks clj-http-ks))
    (let [ks-intersection (set/intersection aleph-ks clj-http-ks)
          clj-http-common-headers (select-keys clj-http-headers ks-intersection)
          aleph-common-headers (select-keys aleph-headers ks-intersection)]
      (is (= clj-http-common-headers aleph-common-headers)))))

(defn is-input-stream=
  "Are the two body InputStreams equal?

   Returns a new ByteArrayInputStream based on the consumed original"
  [^InputStream clj-http-body ^InputStream aleph-body]
  (if clj-http-body
    (do
      (is (some? aleph-body) "Why is aleph body nil? It should be an empty InputStream for now...")
      (let [baos (ByteArrayOutputStream.)]
        (.transferTo clj-http-body baos)                  ; not avail until JDK 9

        (let [clj-http-body-bytes (.toByteArray baos)]
          (is (= (count clj-http-body-bytes)
                 (.available aleph-body)))
          (is (bs/bytes= clj-http-body-bytes aleph-body))

          (proxy [FilterInputStream]
                 [^InputStream (ByteArrayInputStream. clj-http-body-bytes)]
            (close []
              (.close clj-http-body)
              (proxy-super close))))))
    (do
      (is (= clj-http-body aleph-body))
      clj-http-body)))

(defn request
  "Modified version of original, that also sends request via Aleph, and
   tests the responses for equality."
  ([req]
   (request req nil nil))
  ([req respond raise]
   #_(core/request (merge base-req req) respond raise)        ; no aleph

   (if (or respond raise)
     ;; do not attempt to compare when using async clj-http...for now
     (let [ring-map (merge base-req req)]
       (core/request ring-map respond raise))

     (let [ring-map (merge base-req req)
           ;;_ (clojure.pprint/pprint ring-map)
           clj-http-resp (core/request ring-map)
           ;;_ (clojure.pprint/pprint ring-map)
           aleph-resp @(http/request (assoc ring-map :pool no-middleware-pool))]
       (is (= (:status clj-http-resp) (:status aleph-resp)))
       (is-headers= (:headers clj-http-resp) (:headers aleph-resp))
       (let [new-clj-http-body (is-input-stream= (:body clj-http-resp) (:body aleph-resp))]
         (assoc clj-http-resp :body new-clj-http-body))))))
