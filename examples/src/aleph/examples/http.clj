(ns aleph.examples.http
  (:require
    [compojure.core :as compojure :refer [GET]]
    [ring.middleware.params :as params]
    [compojure.route :as route]
    [aleph.http :as http]
    [byte-streams :as bs]
    [manifold.stream :as s]
    [manifold.deferred :as d]))

(defn hello-world-handler
  "A basic Ring handler which immediately returns 'hello world'"
  [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "hello world!"})

(defn delayed-hello-world-handler
  "A non-standard response handler which returns a deferred which yields a Ring response
   after one second.

   This is a non-standard usage of `manifold.deferred/timeout!`, which puts a 'timeout value'
   into a deferred after an interval if it hasn't already been realized.  Here there's nothing
   else trying to touch the deferred, so it will simply yield the 'hello world' response after
   1000 milliseconds."
  [req]
  (d/timeout!
    (d/deferred)
    1000
    (hello-world-handler req)))

(defn streaming-numbers-handler
  [{:keys [params]}]
  (let [cnt (Integer/parseInt (get params "count" "0"))]
    {:status 200
     :headers {"content-type" "text/plain"}
     :body (let [sent (atom 0)]
             (->> (s/periodically 1000 #(str (swap! sent inc) "\n"))
               (s/transform (take cnt))))}))

(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/hello" [] hello-world-handler)
      (GET "/delayed_hello" [] delayed-hello-world-handler)
      (GET "/numbers" [] streaming-numbers-handler))))
