<img src="/docs/aleph.png" align="left" height="210px" hspace="5px"/>

Aleph exposes data from the network as a [Manifold](https://github.com/ztellman/manifold) stream, which can easily be transformed into a `java.io.InputStream`, [core.async](https://github.com/clojure/core.async) channel, Clojure sequence, or [many other byte representations](https://github.com/ztellman/byte-streams).  It exposes simple default wrappers for HTTP, TCP, and UDP, but allows access to full performance and flexibility of the underlying [Netty](https://github.com/netty/netty) library.

```clj
[aleph "0.4.6"]
```

### HTTP

Aleph follows the [Ring](https://github.com/ring-clojure) spec fully, and can be a drop-in replacement for any existing Ring-compliant server.  However, it also allows for the handler function to return a [Manifold deferred](https://github.com/ztellman/manifold) to represent an eventual response.  This feature may not play nicely with synchronous Ring middleware which modifies the response, but this can be easily fixed by reimplementing the middleware using Manifold's [let-flow](https://github.com/ztellman/manifold/blob/master/docs/deferred.md#let-flow) operator. `aleph.http/wrap-ring-async-handler` helper can be used to covert async 3-arity Ring handler to Aleph-compliant one.

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

Aleph attempts to mimic the clj-http API and capabilities fully. It supports multipart/form-data requests, cookie stores, proxy servers and requests inspection with a few notable differences:

* proxy configuration should be set for the connection when seting up a connection pool, per-request proxy setups are not allowed

* HTTP proxy functionality is extended with tunneling settings, optional HTTP headers and connection timeout control, see [all configuration keys](https://github.com/ztellman/aleph/blob/d33c76d6c7d1bf9788369fe6fd9d0e56434c8244/src/aleph/http.clj#L122-L132)

* `:proxy-ignore-hosts` is not supported

* both cookies middleware and built-in cookies storages do not support cookie params obsoleted since RFC2965: comment, comment URL, discard, version (see the full structure of the [cookie](https://github.com/ztellman/aleph/blob/d33c76d6c7d1bf9788369fe6fd9d0e56434c8244/src/aleph/http/client_middleware.clj#L645-L655))

* when using `:debug`, `:save-request?` and `:debug-body?` options, corresponding requests would be stored in `:aleph/netty-request`, `:aleph/request`, `:aleph/request-body` keys of the response map

* `:response-interceptor` option is not supported

* Aleph introduces `:log-activity` connection pool [configuration](https://github.com/ztellman/aleph/blob/d33c76d6c7d1bf9788369fe6fd9d0e56434c8244/src/aleph/http.clj#L120) to switch on the logging of the connections status changes as well as requests/response hex dumps

* `:cache` and `:cache-config` options are not supported as for now

Aleph client also supports fully async and [highly customizable](https://github.com/ztellman/aleph/blob/d33c76d6c7d1bf9788369fe6fd9d0e56434c8244/src/aleph/netty.clj#L783-L796) DNS resolver.

To learn more, [read the example code](http://aleph.io/examples/literate.html#aleph.examples.http).

### WebSockets

On any HTTP request which has the proper `Upgrade` headers, you may call `(aleph.http/websocket-connection req)`, which returns a deferred which yields a **duplex stream**, which uses a single stream to represent bidirectional communication.  Messages from the client can be received via `take!`, and sent to the client via `put!`.  An echo WebSocket handler, then, would just consist of:

```clj
(require '[manifold.stream :as s])

(defn echo-handler [req]
  (let [s @(http/websocket-connection req)]
    (s/connect s s)))
```

This takes all messages from the client, and feeds them back into the duplex socket, returning them to the client.  WebSocket text messages will be emitted as strings, and binary messages as byte arrays.

WebSocket clients can be created via `(aleph.http/websocket-client url)`, which returns a deferred which yields a duplex stream that can send and receive messages from the server.

To learn more, [read the example code](http://aleph.io/examples/literate.html#aleph.examples.websocket).

### TCP

A TCP server is similar to an HTTP server, except that for each connection the handler takes two arguments: a duplex stream and a map containing information about the client.  The stream will emit byte-arrays, which can be coerced into other byte representations using the [byte-streams](https://github.com/ztellman/byte-streams) library.  The stream will accept any messages which can be coerced into a binary representation.

An echo TCP server is very similar to the above WebSocket example:

```clj
(require '[aleph.tcp :as tcp])

(defn echo-handler [s info]
  (s/connect s s))

(tcp/start-server echo-handler {:port 10001})
```

A TCP client can be created via `(aleph.tcp/client {:host "example.com", :port 10001})`, which returns a deferred which yields a duplex stream.

To learn more, [read the example code](http://aleph.io/examples/literate.html#aleph.examples.tcp).

### UDP

A UDP socket can be generated using `(aleph.udp/socket {:port 10001, :broadcast? false})`.  If the `:port` is specified, it will yield a duplex socket which can be used to send and receive messages, which are structured as maps with the following data:

```clj
{:host "example.com"
 :port 10001
 :message ...}
```

Where incoming packets will have a `:message` that is a byte-array, which can be coerced using `byte-streams`, and outgoing packets can be any data which can be coerced to a binary representation.  If no `:port` is specified, the socket can only be used to send messages.

To learn more, [read the documentation](http://aleph.io/examples/literate.html).

### license

Copyright © 2010-2018 Zachary Tellman

Distributed under the MIT License
