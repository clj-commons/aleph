(ns aleph.examples.http
  (:require
    [compojure.core :as compojure :refer [GET]]
    [ring.middleware.params :as params]
    [compojure.route :as route]
    [aleph.http :as http]
    [byte-streams :as bs]
    [manifold.stream :as s]
    [manifold.deferred :as d]))

;; For HTTP, Aleph implements a superset of the
;; [Ring spec](https://github.com/ring-clojure/ring/blob/master/SPEC), which means it can be
;; used as a drop-in replacement for pretty much any other Clojure webserver.  In order to
;; allow for asynchronous responses, however, it enables the use of
;; [Manifold](https://github.com/ztellman/manifold) deferreds and streams.  Uses of both
;; will be illustrated below.

;; Complete documentation for the `aleph.http` namespace can be found [here](http://ideolalia.com/aleph/aleph.http.html).

(defn hello-world-handler
  "A basic Ring handler which immediately returns 'hello world'"
  [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "hello world!"})

(defn delayed-hello-world-handler
  "A non-standard response handler which returns a deferred which yields a Ring response
   after one second.  In a typical Ring-compliant server, this would require holding onto a
   thread via `Thread.sleep()` or a similar mechanism, but the use of a deferred allows for
   the thread to be immediately released without an immediate response.

   This is an atypical usage of `manifold.deferred/timeout!`, which puts a 'timeout value'
   into a deferred after an interval if it hasn't already been realized.  Here there's nothing
   else trying to touch the deferred, so it will simply yield the 'hello world' response after
   1000 milliseconds."
  [req]
  (d/timeout!
    (d/deferred)
    1000
    (hello-world-handler req)))

(defn streaming-numbers-handler
  "Returns a streamed HTTP response, consisting of newline-delimited numbers every 100
   milliseconds.  While this would typically be represented by a lazy sequence, instead we use
   a Manifold stream.  Similar to the use of the deferred above, this means we don't need
   to allocate a thread per-request.

   A lazy sequence can still be used, however, if the upstream provider of data is using an
   `Enumerator` or a similar blocking mechanism for streaming data.

   In this handler we're assuming the string value for `count` is a valid number.  If not,
   `Integer.parseInt()` will throw an exception, and we'll return a `500` status response
   with the stack trace.  If we wanted to be more precise in our status, we'd wrap the parsing
   code with a try/catch that returns a `400` status when the `count` is malformed.

   `manifold.stream/periodically` is similar to Clojure's `repeatedly`, except that it emits
   the value returned by the function at a fixed interval."
  [{:keys [params]}]
  (let [cnt (Integer/parseInt (get params "count" "0"))]
    {:status 200
     :headers {"content-type" "text/plain"}
     :body (let [sent (atom 0)]
             (->> (s/periodically 100 #(str (swap! sent inc) "\n"))
               (s/transform (take cnt))))}))

;; This handler defines a set of endpoints via Compojure's `routes` macro.  Notice that above
;; we've added the `GET` macro via `:refer` so it doesn't have to be qualified.  We wrap the
;; result in `ring.middleware.params/wrap-params` so that we can get the `count` parameter in
;; `streaming-numbers-handler`.
;;
;; Notice that at the bottom we define a default `compojure.route/not-found` handler, which
;; will return a `404` status.  If we don't do this, a call to a URI we don't recognize will
;; return a `nil` response, which will cause Aleph to log an error.
(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/hello" [] hello-world-handler)
      (GET "/delayed_hello" [] delayed-hello-world-handler)
      (GET "/numbers" [] streaming-numbers-handler)
      (route/not-found "No such page."))))

(def s (http/start-server handler {:port 10000}))

(-> @(http/get "http://localhost:10000/hello")
  :body
  bs/to-string
  prn)

(-> @(http/get "http://localhost:10000/delayed_hello")
  :body
  bs/to-string
  prn)

@(d/chain (http/get "http://localhost:10000/delayed_hello")
   :body
   bs/to-string
   prn)

(->> @(http/get "http://localhost:10000/numbers"
        {:query-params {:count 10}})
  :body
  bs/to-line-seq
  (map #(Integer/parseInt %))
  prn)

(.close s)
