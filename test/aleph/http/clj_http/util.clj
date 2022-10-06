(ns aleph.http.clj-http.util
  (:require
    [aleph.http :as http]
    [aleph.http.client-middleware :as aleph.mid]
    [clj-commons.byte-streams :as bs]
    [clj-http.core :as clj-http]
    [clj-http.client]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [clojure.tools.logging :as log])
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

(def ignored-headers ["date" "connection" "server"])

(defn header-keys
  "Returns a set of headers of interest"
  [m]
  (->> (apply dissoc m ignored-headers)
       (keys)
       (map str/lower-case)
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

(defn bodies=
  "Are the two bodies equal? clj-http's client/request fn coerces to strings by default,
   while the core/request leaves the body an InputStream.
   Aleph, in keeping with it's stream-based nature, leaves as an InputStream by default.

   If an InputStream, returns a new ByteArrayInputStream based on the consumed original"
  [clj-http-body ^InputStream aleph-body]
  (if clj-http-body
    (condp instance? clj-http-body
      InputStream
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

      (try
        (do
          (is (bs/bytes= clj-http-body aleph-body))
          clj-http-body)
        (catch Exception e
          (println "clj-http body class: " (class clj-http-body))
          (prn clj-http-body)
          (flush)
          (throw e))))
    (do
      (is (= clj-http-body aleph-body))
      clj-http-body)))


(defn- defined-middleware
  "Returns a set of symbols beginning with `wrap-` in the ns"
  [ns]
  (->> (ns-publics ns)
       keys
       (map str)
       (filter #(str/starts-with? % "wrap-"))
       (map symbol)
       set))

(defn- aleph-test-conn-pool
  "clj-http middleware is traditional fn-based middleware, using a 3-arity version to handle async.

   Aleph usually uses a more async-friendly interceptor-style, where the middleware transforms the request maps,
   but does nothing about calling the next fn in the chain.

   Unfortunately, a couple middleware cannot be converted to interceptor-style, complicating things."
  [middleware-list]
  (let [missing-midw (set/difference
                       (defined-middleware 'clj-http.client)
                       (defined-middleware 'aleph.http.client-middleware))]
    (when-not (seq missing-midw)
      (println "clj-http is using middleware that aleph lacks:")
      (prn missing-midw)
      (log/warn "clj-http is using middleware that aleph lacks"
                :missing-middleware missing-midw)))
  (let [non-interceptor-middleware (set aleph.mid/default-client-middleware)
        client-middleware (cond-> []
                                  (some #{clj-http.client/wrap-exceptions} middleware-list)
                                  (conj aleph.mid/wrap-exceptions)

                                  (some #{clj-http.client/wrap-request-timing} middleware-list)
                                  (conj aleph.mid/wrap-request-timing))
        middleware-list' (->> middleware-list
                              (map (fn [midw]
                                      (-> midw
                                          class
                                          str
                                          (str/split #"\$")
                                          peek
                                          (str/replace "_" "-")
                                          (->> (symbol "aleph.http.client-middleware"))
                                          requiring-resolve)))
                              (filter some?)
                              (map var-get)
                              (remove non-interceptor-middleware)
                              vec)]
    ;;(println "Client-based middleware:")
    ;;(prn client-middleware)
    ;;(println "Regular middleware:")
    ;;(prn middleware-list')
    (http/connection-pool {:middleware #(aleph.mid/wrap-request % client-middleware middleware-list')})))


(defn- print-middleware-list
  [middleware-list]
  (prn (mapv (fn [midw]
               (-> midw
                   class
                   str
                   (str/split #"\$")
                   peek
                   (str/replace "_" "-")
                   symbol))
             middleware-list)))

(defn make-request
  "Need to switch between clj-http's core/request and client/request.

   Modified version of original request fns, that also sends requests
   via Aleph, and tests the responses for equality."
  [clj-http-request {:keys [using-middleware?]}]
  (fn compare-request
    ([req]
     (compare-request req nil nil))
    ([req respond raise]
     (if (or respond raise)
       ;; do not attempt to compare when using async clj-http...for now
       (let [ring-map (merge base-req req)]
         (clj-http-request ring-map respond raise))

       (let [clj-http-ring-map (merge base-req req)
             ;;_ (prn clj-http-ring-map)
             clj-http-middleware (if using-middleware? clj-http.client/*current-middleware* [])
             ;;_ (print-middleware-list clj-http.client/*current-middleware*)
             aleph-ring-map (merge base-req req {:pool (aleph-test-conn-pool clj-http-middleware)})
             ;;_ (prn aleph-ring-map)
             clj-http-resp (clj-http-request clj-http-ring-map)
             aleph-resp @(http/request aleph-ring-map)]
         (is (= (:status clj-http-resp) (:status aleph-resp)))

         (prn aleph-resp)

         #_(when (not= (:status clj-http-resp) (:status aleph-resp))
           (println "clj-http req:")
           (prn clj-http-ring-map)
           (println)
           (println "clj-http resp:")
           (prn clj-http-resp)
           (println)
           (println)
           (println "aleph req:")
           (prn aleph-ring-map)
           (println)
           (println "aleph resp:")
           (prn aleph-resp))

         (is-headers= (:headers clj-http-resp) (:headers aleph-resp))
         (is (instance? InputStream (:body aleph-resp)))
         (let [new-clj-http-body (bodies= (:body clj-http-resp) (:body aleph-resp))]
           (assoc clj-http-resp :body new-clj-http-body)))))))
