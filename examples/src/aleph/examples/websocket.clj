(ns aleph.examples.websocket
  (:require
    [compojure.core :as compojure :refer [GET]]
    [ring.middleware.params :as params]
    [compojure.route :as route]
    [aleph.http :as http]
    [byte-streams :as bs]
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [manifold.bus :as bus]
    [clojure.core.async :as a]))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn echo-handler
  "This handler sets up a websocket connection, and then proceeds to echo back every message
   it receives from the client.  The value yielded by `websocket-connection` is a **duplex
   stream**, which represents communication to and from the client.  Therefore, all we need to
   do in order to echo the messages is connect the stream to itself.

   Since any request it gets may not be a valid handshake for a websocket request, we need to
   handle that case appropriately."
  [req]
  (if-let [socket (try
                    @(http/websocket-connection req)
                    (catch Exception e
                      nil))]
    (s/connect socket socket)
    non-websocket-request))

(defn echo-handler
  "The previous handler blocks until the websocket handshake completes, which unnecessarily
   takes up a thread.  This accomplishes the same as above, but asynchronously. "
  [req]
  (-> (http/websocket-connection req)
    (d/chain
      (fn [socket]
        (s/connect socket socket)))
    (d/catch
      (fn [_]
        non-websocket-request))))

(defn echo-handler
  "This is another asynchronous handler, but uses `let-flow` instead of `chain` to define the
   handler in a way that at least somewhat resembles the synchronous handler."
  [req]
  (->
    (d/let-flow [socket (http/websocket-connection req)]
      (s/connect socket socket))
    (d/catch
      (fn [_]
        non-websocket-request))))

;; to represent all the different chat rooms, we use an **event bus**, which is simple
;; implementation of the publish/subscribe model
(def chatrooms (bus/event-bus))

(defn chat-handler
  [req]
  (d/let-flow [conn (d/catch
                      (http/websocket-connection req)
                      (fn [_] nil))]

    (if-not conn

      ;; if it wasn't a valid websocket handshake, return an error
      non-websocket-request

      ;; otherwise, take the first two messages, which give us the chatroom and name
      (d/let-flow [room (s/take! conn)
                   name (s/take! conn)]

        ;; take all messages from the chatroom, and feed them to the client
        (s/connect
          (bus/subscribe chatrooms room)
          conn)

        ;; take all messages from the client, prepend the name, and publish it to the room
        (s/consume
          #(bus/publish! chatrooms room %)
          (->> conn
            (s/map #(str name ": " %))
            (s/buffer 100)))))))

(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/echo" [] echo-handler)
      (GET "/chat" [] chat-handler)
      (route/not-found "No such page."))))


(def s (http/start-server handler {:port 10000}))

;; Here we `put!` ten messages to the server, and read them back again
(let [conn @(http/websocket-client "ws://localhost:10000/echo")]

  (s/put-all! conn
    (->> 10 range (map str)))

  (->> conn
    (s/transform (take 10))
    s/stream->seq
    doall))    ;=> ("0" "1" "2" "3" "4" "5" "6" "7" "8" "9")

;; Here we create two clients, and have them speak to each other
(let [conn1 @(http/websocket-client "ws://localhost:10000/chat")
      conn2 @(http/websocket-client "ws://localhost:10000/chat")
      ]

  ;; sign our two users in
  (s/put-all! conn1 ["shoes and ships" "Alice"])
  (s/put-all! conn2 ["shoes and ships" "Bob"])

  (s/put! conn1 "hello")

  @(s/take! conn1)   ;=> "Alice: hello"
  @(s/take! conn2)   ;=> "Alice: hello"

  (s/put! conn2 "hi!")

  @(s/take! conn1)   ;=> "Bob: hi!"
  @(s/take! conn2)   ;=> "Bob: hi!"
  )

(.close s)
