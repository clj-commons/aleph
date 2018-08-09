### 0.4.0

* from scratch rewrite, too many changes to name

### 0.4.1

* altered shape of `manifold.stream/description` for Netty sources and sinks
* made instrumentation of individual connections optional, as it was having a small but measurable performance impact around ~100k connections
* target manifold 0.1.4, byte-streams 0.2.2

### 0.4.2

Thanks to Zak Kristjanson, Elana Hashman, Casey Marshall, Jeroen van Dijk, Cameron Desautels, Leon Mergen, Ryan Waters, Nate Young, and Martin Klepsch

* allow for `:ssl-context` to be defined for clients
* add `aleph.netty/ssl-client-context`, and basic example of SSL configuration
* add websocket close status and message to stream description
* match nested query param behavior in clj-http
* target manifold 0.1.6
* make all threads daemon by default
* add `aleph.netty/wait-for-close` method, to prevent the process from closing prematurely

### 0.4.3

Thanks to Dominic Monroe

* fix bug in client ssl-context creation

### 0.4.4

Thanks to Antony Woods, Alexey Kachayev, Ivan Kryvoruchko, Yonatan Elhanan, Daniel Truemper

* fix memory lead in handling of binary websocket frames
* fix handling of decoder errors in Netty's HTTP stack

### 0.4.6

Thanks to Alexey Kachayev, Yoan Blanc, Christian Karlsen, Jeremie Grodziski

* Add support for WebSocket `PING` frames
* Add support for cookies in HTTP client
* Add `:dns-resolver` option for all client protocols
* Add proxy support for HTTP client
