(ns aleph.examples.tcp
  (:require
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [clojure.edn :as edn]
    [aleph.tcp :as tcp]
    [gloss.core :as gloss]
    [gloss.io :as io]))

;; Complete documentation for the `aleph.tcp` namespace can be found [here](http://aleph.io/codox/aleph/aleph.tcp.html).

;; ## the basics

;; This uses [Gloss](https://github.com/ztellman/gloss), which is a library for defining byte
;; formats, which are automatically compiled into encoder and streaming decoders.
;;
;; Here, we define a simple protocol where each frame starts with a 32-bit integer describing
;; the length of the string which follows.  We assume the string is EDN-encoded, and so we
;; define a `pre-encoder` of `pr-str`, which will turn our arbitrary value into a string, and
;; a `post-decoder` of `clojure.edn/read-string`, which will transform our string into a data
;; structure.
(def protocol
  (gloss/compile-frame
    (gloss/finite-frame :uint32
      (gloss/string :utf-8))
    pr-str
    edn/read-string))

;; This function takes a raw TCP **duplex stream** which represents bidirectional communication
;; via a single stream.  Messages from the remote endpoint can be consumed via `take!`, and
;; messages can be sent to the remote endpoint via `put!`.  It returns a duplex stream which
;; will take and emit arbitrary Clojure data, via the protocol we've just defined.
;;
;; First, we define a connection between `out` and the raw stream, which will take all the
;; messages from `out` and encode them before passing them onto the raw stream.
;;
;; Then, we `splice` together a separate sink and source, so that they can be presented as a
;; single duplex stream.  We've already defined our sink, which will encode all outgoing
;; messages.  We must combine that with a decoded view of the incoming stream, which is
;; accomplished via `gloss.io/decode-stream`.
(defn wrap-duplex-stream
  [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(io/encode protocol %) out)
      s)

    (s/splice
      out
      (io/decode-stream s protocol))))

;; The call to `aleph.tcp/client` returns a deferred, which will yield a duplex stream that
;; can be used to both send and receive bytes. We asynchronously compose over this value using
;; `manifold.deferred/chain`, which will wait for the client to be realized, and then pass
;; the client into `wrap-duplex-stream`.  The call to `chain` will return immediately with a
;; deferred value representing the eventual wrapped stream.
(defn client
  [host port]
  (d/chain (tcp/client {:host host, :port port})
    #(wrap-duplex-stream protocol %)))

;; Takes a two-argument `handler` function, which takes a stream and information about the
;; connection, and sets up message handling for the stream.  The raw stream is wrapped in the
;; Gloss protocol before being passed into `handler`.
(defn start-server
  [handler port]
  (tcp/start-server
    (fn [s info]
      (handler (wrap-duplex-stream protocol s) info))
    {:port port}))

;; ## echo servers

;; This creates a handler which will apply `f` to any incoming message, and immediately
;; send back the result.  Notice that we are connecting `s` to itself, but since it is a duplex
;; stream this is simply an easy way to create an echo server.
(defn fast-echo-handler
  [f]
  (fn [s info]
    (s/connect
      (s/map f s)
      s)))

;; ### demonstration

;; We start a server `s` which will return incremented numbers.
(def s
  (start-server
    (fast-echo-handler inc)
    10000))

;; We connect a client to the server, dereferencing the deferred value returned such that `c`
;; is simply a duplex stream that takes and emits Clojure values.
(def c @(client "localhost" 10000))

;; We `put!` a value into the stream, which is encoded to bytes and sent as a TCP packet.  Since
;; TCP is a streaming protocol, it is not guaranteed to arrive as a single packet, so the server
;; must be robust to split messages.  Since both client and server are using Gloss codecs, this
;; is automatic.
@(s/put! c 1)  ; => true

;; The message is parsed by the server, and the response is sent, which again may be split
;; while in transit between the server and client.  The bytes are consumed and parsed by
;; `wrap-duplex-stream`, and the decoded message can be received via `take!`.
@(s/take! c)   ; => 2

;; The server implements `java.io.Closeable`, and can be stopped by calling `close()`.
(.close s)

;; ### end demonstration

;; While we can do trivial computation on the same thread we receive messages, longer computation
;; or blocking operations should be done elsewhere.  To accomplish this, we need something a
;; little more complicated than `connect`ing a stream to itself.
;;
;; Here, we define an asynchronous loop via `manifold.deferred/loop`.  In this loop, we take a
;; message from the stream, transform it on another thread with `manifold.deferred/future`,
;; send it back, and then repeat.
(defn slow-echo-handler
  [f]
  (fn [s info]
    (d/loop []

      ;; take a message, and define a default value that tells us if the connection is closed
      (-> (s/take! s ::none)

        (d/chain

          ;; first, check if there even was a message, and then transform it on another thread
          (fn [msg]
            (if (= ::none msg)
              ::none
              (d/future (f msg))))

          ;; once the transformation is complete, write it back to the client
          (fn [msg']
            (when-not (= ::none msg')
              (s/put! s msg')))

          ;; if we were successful in our response, recur and repeat
          (fn [result]
            (when result
              (d/recur))))

        ;; if there were any issues on the far end, send a stringified exception back
        ;; and close the connection
        (d/catch
          (fn [ex]
            (s/put! s (str "ERROR: " ex))
            (s/close! s)))))))

;; Alternately, we use `manifold.deferred/let-flow` to implement the composition of these
;; asynchronous values.  It is certainly more concise, but at the cost of being less explicit.
(defn slow-echo-handler
  [f]
  (fn [s info]
    (d/loop []
      (->
        (d/let-flow [msg (s/take! s ::none)]
          (when-not (= ::none msg)
            (d/let-flow [msg'   (d/future (f msg))
                         result (s/put! s msg')]
              (when result
                (d/recur)))))
        (d/catch
          (fn [ex]
            (s/put! s (str "ERROR: " ex))
            (s/close! s)))))))

;; ### demonstration

;; We start a server `s` which will return incremented numbers, slowly.
(def s
  (start-server
    (slow-echo-handler
      (fn [x]
        (Thread/sleep 1000)
        (inc x)))
    10000))

(def c @(client "localhost" 10000))

@(s/put! c 1)  ; => true

@(s/take! c)   ; => 2

(.close s)

;; ### end demonstration
