(ns aleph.examples.http
  (:import
    [io.netty.handler.ssl SslContextBuilder])
  (:require
    [compojure.core :as compojure :refer [GET]]
    [ring.middleware.params :as params]
    [compojure.route :as route]
    [compojure.response :refer [Renderable]]
    [aleph.http :as http]
    [byte-streams :as bs]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [clojure.core.async :as a]
    [clojure.java.io :refer [file]]))

;; For HTTP, Aleph implements a superset of the
;; [Ring spec](https://github.com/ring-clojure/ring/blob/master/SPEC), which means it can be
;; used as a drop-in replacement for pretty much any other Clojure webserver.  In order to
;; allow for asynchronous responses, however, it allows for the use of
;; [Manifold](https://github.com/ztellman/manifold) deferreds and streams.  Uses of both
;; will be illustrated below.

;; Complete documentation for the `aleph.http` namespace can be found [here](http://aleph.io/codox/aleph/aleph.http.html).

;; ## building servers

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

;; Compojure will normally dereference deferreds and return the realized value.
;; Unfortunately, this blocks the thread. Since Aleph can accept the unrealized
;; deferred, we extend Compojure's `Renderable` protocol to pass the deferred
;; through unchanged so it can be handled asynchronously.
(extend-protocol Renderable
  manifold.deferred.IDeferred
  (render [d _] d))

(defn delayed-hello-world-handler
  "Alternately, we can use a [core.async](https://github.com/clojure/core.async) goroutine to
   create our response, and convert the channel it returns using
   `manifold.deferred/->source`, and then take the first message from it. This is entirely equivalent
   to the previous implementation."
  [req]
  (s/take!
    (s/->source
      (a/go
        (let [_ (a/<! (a/timeout 1000))]
          (hello-world-handler req))))))

(defn streaming-numbers-handler
  "Returns a streamed HTTP response, consisting of newline-delimited numbers every 100
   milliseconds.  While this would typically be represented by a lazy sequence, instead we use
   a Manifold stream.  Similar to the use of the deferred above, this means we don't need
   to allocate a thread per-request.

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

(defn streaming-numbers-handler
  "However, we can always still use lazy sequences. This is still useful when the upstream
   data provider exposes the stream of data as an `Iterator` or a similar blocking mechanism.
   This will, however, hold onto a thread until the sequence is exhausted."
  [{:keys [params]}]
  (let [cnt (Integer/parseInt (get params "count" "0"))]
    {:status 200
     :headers {"content-type" "text/plain"}
     :body (->> (range cnt)
             (map #(do (Thread/sleep 100) %))
             (map #(str % "\n")))}))

(defn streaming-numbers-handler
  "We can also take a core.async channel, coerce it to a Manifold source via
   `manifold.stream/->source`.  All three implementations of `streaming-numbers-handler` are
   equivalent."
  [{:keys [params]}]
  (let [cnt (Integer/parseInt (get params "count" "0"))
        body (a/chan)]

    ;; create a goroutine that emits incrementing numbers once every 100 milliseconds
    (a/go-loop [i 0]
      (if (< i cnt)
        (let [_ (a/<! (a/timeout 100))]
          (a/>! body (str i "\n"))
          (recur (inc i)))
        (a/close! body)))

    ;; return a response containing the coerced channel as the body
    {:status 200
     :headers {"content-type" "text/plain"}
     :body (s/->source body)}))

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
      (GET "/hello"         [] hello-world-handler)
      (GET "/delayed_hello" [] delayed-hello-world-handler)
      (GET "/numbers"       [] streaming-numbers-handler)
      (route/not-found "No such page."))))

(def s (http/start-server handler {:port 10000}))

;; ## using the http client

;; Here we immediately dereference the response, get the `:body`, which is an InputStream,
;; and coerce it to a string using `byte-strings/to-string`.
(-> @(http/get "http://localhost:10000/hello")
  :body
  bs/to-string)   ;=> "hello world!"

;; And we do the same exact thing for the `delayed_hello` endpoint, which returns an identical
;; result after a second's pause.
(-> @(http/get "http://localhost:10000/delayed_hello")
  :body
  bs/to-string)   ;=> (beat) "hello world!"

;; Instead of dereferencing the response, we can use `manifold.deferred/chain` to compose
;; operations over it. Here we dereference the final result so that we don't close the server
;; before the response is complete, but we could also perform some side effect further down
;; the chain if we want to completely avoid blocking operations.
@(d/chain (http/get "http://localhost:10000/delayed_hello")
   :body
   bs/to-string) ;=> (beat) "hello world!"

;; Here we take a line-delimited streaming response, and coerce it to a lazy sequence of
;; strings via `byte-streams/to-line-seq`.
(->> @(http/get "http://localhost:10000/numbers"
        {:query-params {:count 10}})
  :body
  bs/to-line-seq
  (map #(Integer/parseInt %))
  doall)   ;=> (0 1 2 3 4 5 6 7 8 9)

;; By default, the `:body` of any response is a `java.io.InputStream`.  However, this means
;; that our consumption of the body needs to be synchronous, as shown above by coercing it
;; to a Clojure seq.  If we want to have the body be asynchronous, we need to specify
;; `:raw-stream?` to be `true` for request connection pool.
@(d/chain
   (http/get "http://localhost:10000/numbers"
     {:query-params {:count 10}
      :pool (http/connection-pool {:connection-options {:raw-stream? true}})})
   :body
   #(s/map bs/to-byte-array %)
   #(s/reduce conj [] %)
   bs/to-string)

;; In the above example, we coerce a stream of Netty `ByteBuf` objects into a stream of `byte[]`
;; before asynchronously accumulating them and coercing the entire collection into a `String`
;; once the stream is exhausted.
;;
;; This is a useful indirection, since `ByteBuf` objects are
;; [reference counted](http://netty.io/wiki/reference-counted-objects.html), and generally
;; add a lot of complexity to the code in all but the simplest use cases.  Coercing the `ByteBuf`
;; objects to any other form (`byte[]`, `String`, etc.) will copy the bytes and decrement
;; the ref-count, which is almost always what's wanted.
;;
;; However, if we're simply forwarding the bytes to another network socket, or want to minimize
;; memory copies at all costs, leaving the `ByteBuf` objects in their native form is the way to
;; go.

(.close s)

;; ### TLS client certificate authentication

;; Aleph also supports TLS client certificate authentication. To make such connections, we must
;; build a custom SSL context and pass it to a connection pool that we'll use to make HTTP requests.

(defn build-ssl-context
  "Given the certificate authority (ca)'s certificate, a client certificate, and the key for the
  client certificate in PKCS#8 format, we can build an SSL context for mutual TLS authentication."
  [ca cert key]
  (-> (SslContextBuilder/forClient)
      (.trustManager (file ca))
      (.keyManager (file cert) (file key))
      .build))

(defn ssl-connection-pool
  "To use the SSL context, we set the `:ssl-context` connection option on a connection pool. This
  allows the pool to make TLS connections with our client certificate."
  [ca cert key]
  (http/connection-pool
   {:connection-options
    {:ssl-context (build-ssl-context ca cert key)}}))

;; We can use our `ssl-connection-pool` builder to GET pages from our target endpoint by passing the
;; `:pool` option to `aleph.http/get`.

@(d/chain
  (http/get
   "https://server.with.tls.client.auth"
   {:pool (ssl-connection-pool "path/to/ca.crt" "path/to/cert.crt" "path/to/key.k8")})
  :body
  bs/to-string)
