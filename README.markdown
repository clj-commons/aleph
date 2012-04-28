Aleph is a Clojure framework for asynchronous communication, built on top of [Netty](http://www.jboss.org/netty) and [Lamina](http://github.com/ztellman/lamina).


### What is Aleph good for? ###

Aleph allows the creation of both clients and servers that can communicate using an array of protocols (HTTP, WebSockets, TCP, UDP, and others), and represents that communication via a single abstraction, [channels](https://github.com/ztellman/lamina/wiki/Channels).  Thanks to the underlying libraries and the event-driven approach to communication, these clients and servers can be highly scalable.

### Using Aleph in your project ###

In the project.clj file at the top level of your project, add Aleph as a dependency:

```clj
(defproject my-project "1.0.0"
  :dependencies [[org.clojure/clojure "1.2.1"]
				 [aleph "0.2.1-beta2"]])
```

## Code examples ##


### HTTP Server ###

Aleph conforms to the interface described by [Ring](http://github.com/mmcgrana/ring), with one small difference: the request and response are decoupled.

```clj
(use 'lamina.core 'aleph.http)

(defn hello-world [channel request]
  (enqueue channel
    {:status 200
     :headers {"content-type" "text/html"}
     :body "Hello World!"}))

(start-http-server hello-world {:port 8080})
```

For more on HTTP functionality, read the [wiki](https://github.com/ztellman/aleph/wiki/HTTP).


### HTTP Client ###

This snippet prints out a never-ending sequence of tweets:

```clj
(use 'lamina.core 'aleph.http 'aleph.formats)

(let [ch (:body
           (sync-http-request
             {:method :get
              :basic-auth ["aleph_example" "_password"]
              :url "https://stream.twitter.com/1/statuses/sample.json"
              :delimiters ["\r"]}))]
  (doseq [tweet (map decode-json (lazy-channel-seq ch))]
    (prn tweet)))
```

A more in-depth exploration of this example can be found [here](http://github.com/ztellman/aleph/wiki/Consuming-and-Broadcasting-a-Twitter-Stream).


### WebSockets ###

Making a simple chat client is trivial.  In this, we assume that the first message sent by the client is the user's name:

```clj
(use 'lamina.core 'aleph.http)

(def broadcast-channel (channel))

(defn chat-handler [ch handshake]
  (receive ch
    (fn [name]
      (siphon (map* #(str name ": " %) ch) broadcast-channel)
      (siphon broadcast-channel ch))))

(start-http-server chat-handler {:port 8080 :websocket true})
```


### TCP Client/Server ###

Here is a basic echo server:

```clj
(use 'lamina.core 'aleph.tcp)

(defn echo-handler [channel client-info]
  (siphon channel channel))

(start-tcp-server echo-handler {:port 1234})
```

For more on TCP functionality, visit the [wiki](https://github.com/ztellman/aleph/wiki/TCP).

--

Other protocols are supported, and still more are forthcoming.

Aleph is meant to be a sandbox for exploring how Clojure can be used effectively in this context.  Contributions and ideas are welcome.

For more information, visit the [wiki](https://github.com/ztellman/aleph/wiki) or the [API documentation](http://ztellman.github.com/aleph/index.html).  If you have questions, please visit the [mailing list](http://groups.google.com/group/aleph-lib).
