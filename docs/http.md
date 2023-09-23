# HTTP

Aleph follows the [Ring](https://github.com/ring-clojure) spec fully, and can 
be a drop-in replacement for any existing Ring-compliant server.  However, it 
also allows for the handler function to return a 
[Manifold deferred](https://github.com/clj-commons/manifold) to represent an 
eventual response.  This feature may not play nicely with Ring middleware which 
modifies the response, but this can be easily fixed by reimplementing the 
middleware using Manifold's 
[let-flow](https://cljdoc.org/d/manifold/manifold/CURRENT/api/manifold.deferred#let-flow) 
operator.

```clj
(require '[aleph.http :as http])

(defn handler [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "hello!"})

(http/start-server handler {:port 8080})
```

The body of the response may also be a Manifold stream, where each message from the stream is sent as a chunk, allowing for precise control over streamed responses for [server-sent events](http://en.wikipedia.org/wiki/Server-sent_events) and other purposes.

For HTTP client requests, Aleph models itself after [clj-http](https://github.com/dakrone/clj-http), except that every request immediately returns a Manifold deferred representing the response.

```clj
(require
  '[manifold.deferred :as d]
  '[byte-streams :as bs])

(-> @(http/get "https://google.com/")
  :body
  bs/to-string
  prn)

(d/chain (http/get "https://google.com")
  :body
  bs/to-string
  prn)
```

To learn more, [read the example code](https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/http.clj).

### WebSockets

On any HTTP request which has the proper `Upgrade` headers, you may call `(aleph.http/websocket-connection req)`, which returns a deferred which yields a **duplex stream**, which uses a single stream to represent bidirectional communication.  Messages from the client can be received via `take!`, and sent to the client via `put!`.  An echo WebSocket handler, then, would just consist of:

```clj
(require '[manifold.stream :as s])

(defn echo-handler [req]
  (let [s @(http/websocket-connection req)]
    (s/connect s s))))
```

This takes all messages from the client, and feeds them back into the duplex socket, returning them to the client.  WebSocket text messages will be emitted as strings, and binary messages as byte arrays.

WebSocket clients can be created via `(aleph.http/websocket-client url)`, which returns a deferred which yields a duplex stream that can send and receive messages from the server.

To learn more, [read the example code](https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/websocket.clj).

