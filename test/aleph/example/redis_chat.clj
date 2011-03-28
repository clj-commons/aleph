(ns aleph.example.redis-chat
  (:use
    [lamina core]
    [aleph redis tcp]
    [gloss core]))

(def redis-address "localhost")

(defn chatroom [room-name]
  ;; when creating a new chat channel, we create a no-op callback so messages don't persist
  (named-channel chatroom #(receive-all % (fn [_]))))

(defn init []
  
  ;; create the client we'll use to publish messages
  (def publisher (redis-client {:host redis-address}))

  ;; create the stream we'll use to consume messages, and subscribe to all chatrooms
  (def consumer (redis-stream {:host redis-address}))
  (pattern-subscribe consumer "*")

  ;; consume all messages from the stream, and distribute them to the correct chat channel
  (receive-all consumer
    (fn [{room-name :channel, message :message}]
      (enqueue (chatroom room-name) message))))

;; we have this broken out into a separate function because none of it is async,
;; and we only want to pay the async performance tax where necessary
(defn initialize-chat [ch user-name room-name]
  
  ;; forward every message from the chat channel to the user
  (siphon (chatroom room-name) ch)

  ;; forward every message from the user to Redis, where it will be broadcast
  ;; to everyone
  (receive-all (map* #(str user-name ": " %) ch)
    #(publisher ["publish" (str "chat." room-name) %])))

(defn connection-handler [ch connection-info]
  ;; this may look synchronous, but it's not - no dedicated threads are necessary
  (async
    (let [_ (enqueue ch "What's your name?")
	  user-name (read-channel ch)
	  _ (enqueue ch "Where do you want to chat?")
	  room-name (read-channel ch)]
      (initialize-chat ch user-name room-name))))

;; run this, then connect via telnet ('telnet localhost 10000' at the command line)
;; you can even open multiple sessions, and chat with yourself
(defn start-redis-example []
  (init)
  (def stop-server
    (start-tcp-server
      connection-handler
      {:port 10000 :frame (string :utf-8 :delimiters ["\n" "\r\n"])})))
