# TCP

A TCP server is similar to an HTTP server, except that for each connection the handler takes two arguments: a duplex stream and a map containing information about the client.  The stream will emit byte-arrays, which can be coerced into other byte representations using the [byte-streams](https://github.com/clj-commons/byte-streams) library.  The stream will accept any messages which can be coerced into a binary representation.

An echo TCP server is very similar to the above WebSocket example:

```clj
(require '[aleph.tcp :as tcp])

(defn echo-handler [s info]
  (s/connect s s))

(tcp/start-server echo-handler {:port 10001})
```

A TCP client can be created via `(aleph.tcp/client {:host "example.com", :port 10001})`, which returns a deferred which yields a duplex stream.

To learn more, [read the example code](https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/tcp.clj).
