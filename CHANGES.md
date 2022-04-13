### 0.5.0-SNAPSHOT

* Add initial clj-kondo hooks
* Minor bugfix in examples code
* Add pipeline-transform test
* Add missing type hint in websocket-server-handler
* Correctly handle too large headers/URIs
* Add doc for undocumented response-executor parameter
* Minor bugfix for keep-alive? false
* Fixed major memory leak when sending InputStreams
* Fixed bug when sending empty files
* Fix returned filename in multipart uploads for clj-http parity
* Ensure client exceptions handled and channel closed on invalid input

Contributions by Arnaud Geiser, Moritz Heidkamp, and Matthew Davidson

### 0.4.7

Contributions by (in alphabetical order):

Erik Assum, Yoan Blanc, Reynald Borer, Michael Cameron, Jonathan Chen, Daniel Compton, Matthew Davidson, Rafal Dittwald, 
Ganesh Gautam, Aaron Muir Hamilton, Moritz Heidkamp, Alexey Kachayev, Dominic Pearson, Matthew Phillips, Denis Shilov, 
Zach Tellman, Mark Wardle, and Alexander Yakushev

#### 0.4.7-rc3

* Bumped up Netty to 4.1.65.Final for a regression
* Added `javac` options to force 1.8-compatible classes

#### 0.4.7-rc2

* Fixed regression compiling for later Java 17

#### 0.4.7-rc1

* Remove unused Travis config
* Remove test dependency on /usr/share/dict/words
* Remove 1.7-specific java options
* Fix SSL self-generated cert test
* Update cljdoc badge
* Add URL to project.clj

#### 0.4.7-alpha10

* Bump byte-streams version
* Update .gitignore for clj-kondo and lsp

#### 0.4.7-alpha9

* Update dependencies.

#### 0.4.7-alpha8

* Moving to newer netty 4.1.64.Final
* Extract dns-resolver-group builder code into a separate function (#564)
* [Fixes #561] Fix websocket-upgrade-request? for Firefox. (#562)
* Create ORIGINATOR
* Create CODEOWNERS
* Update README.md
* Circleci project setup (#554)
* Update README.md
* Add badges

#### 0.4.7-alpha7

* Avoid NullPointerException if optional timeout omitted. (#551)
* Derive part-name from filename (when necessary) to improve compatibility between multipart interfaces, resolves #519
* Remove logic around "Proxy-Connection: Keep-Alive" header, RFC 7230, A.1.2 defines header as obsolete
* Optimize HTTP request baking by pre-computing static settings (e.g. proxies) when setting up the connection
* Avoid duplicating query params when using proxy (#548)
* Upgrade dependencies to the latest versions, new alpha tag (alpha5 -> alpha6)
* Define SSL connection pool before executing request
* Update LICENSE file to be consistent with README
* Improve raw-stream example
* Handle 308 Permanent Redirect (same behaviour than for 307)
* Add license
* Properly initialize per-message deflate handshaker for websocket server, covers #494 (#506)
* Helper to extract SSL session from the request (#505)
* Support WebSocketChunkedInput in websockets (#503)
* Support raw streams for text websockets (#502)
* Accurate processing for proxy connection exception (#509)
* 100-Continue handler (#482)
* Upgrade Netty to 4.1.36.Final (#507)
* Use shared instance of logging handler for the connection pool (#495)
* http/file API to send region of the file (#485)
* Use Epoll datagram channel for DNS resolver group when running on Epoll (#477)
* Show the server's channel when printing AlephServer object (#491)
* Get rid of deprecated HttpHeaders methods (#497)
* Bump to Netty 4.1.34.Final (#496)
* WebSocket connection to expose more information about handshake result  (#498)
* Fine-grained websocket close handshake API (#481)
* Carefully release buffers when processing websocket client stream (#490)
* Add more aggressive leak detection to all tests

#### 0.4.7-alpha5

* Fix ExceptionInInitializerError in reified GenericFutureListener  (#425)
* Carefully release ByteBuf when processing WebSocketFrame (#430)
* Avoid releasing non-reference counted body (#437)
* Minor fixes in client_middleware.clj (#429)
* Use defonce to prevent rebinding of thread locals (#438)
* Do not call address types setter for DNS resolver with NULL to stick to defaults, fixes #467 (#468)
* Support manual ping/pong messages over websocket connections (#364)
* Bump to Netty 4.1.33.Final (#464)
* Fix reflection warning calling java.net.URLEncoder/encode (#436)
* Async Ring handlers wrapper  (#442)

#### 0.4.7-alpha4

* Bump to Netty 4.1.32.Final (#433)
* Switch to use thread factory from manifold.executor (#426)
* Use a better workaround for Compojure, fixes #424
* Modify client/client-handler to support HttpObjectAggregator (#393)
* Multipart decoder (#411)
* Target latest dependencies, mark 0.4.7-alpha3
* Added ssl-option for WebSocket client (#419)
* Add :manual-ssl? option to HTTP start-server. (#423)
* Handle WS handshake exceptions

#### 0.4.7-alpha2

* Bump to Netty 4.1.30.Final
* Make sure our post-websocket upgrade 'response' is always nil in the examples
* Target latest netty version, and clean up SSL tests
* Examples: update the deps
* Mentioned DNS, outlined specific differences in the API
* Use built-in ChannelInitializer to build pipeline when Channel is registered
* Remove unused netty/HeaderMap declaration
* Remove assert as we know that we always have chuncked writer in our pipeline
* Mark aleph.http.multipart/encode-body as deprecated
* File upload to support manually specified charset
* Support binary content parts and custom charsets
* Cleaning up code from client connection handling
* Test case to actually send multipart request to the server
* If multipart encoder didn't claim the content being chunked, we're safe to send nothing
* Permanently added ChunkedWriteHandler to deal with potential multipart upload
* Netty's multipart encoder may return either full request or chunked stream
* Close context instead of channel to short-circuit event propagation
* Update README file to mention support for multipart, cookie stores and proxy
* Fix response executor affinity test case
* Test case to cover :save-request? option
* Update formatting for future-with call
* Fix imports
* Support custom date header
* Reimplemt :save-request internals to save Netty's HttpMessage
* Slightly better description for the :log-activity option
* Introduce :log-activity configuration for connection to enable logging
* Fisrt attempt to introduce :save-request? option into the client
* Get rid of depricated SslContext methods
* Get rid of the hack with PluggableDnsAddressResolverGroup
* Simplified tests using map-indexed
* Pipeline initializer is effectively inbound handler, removed redundant event
* Get rid of Netty's deprecated methods from http/server
* Get rid of deprecated Netty methods from http/core
* coerce-element helper for streaming body does not need netty channel as it does not perform allocation
* Make sure an empty "Set-Cookie" response header doesn't crash default middleware
* Allow to set log4j2 logger
* Remove Netty deprecated methods usage in aleph.http.client ns
* Fix typo and remove unused namespaces
* Document follow-redirects? as the handler provides extra options

#### 0.4.7-alpha1 

* fix #391, mark 0.4.7-alpha1


### 0.4.6

Thanks to Alexey Kachayev, Yoan Blanc, Christian Karlsen, Jeremie Grodziski

* Add support for WebSocket `PING` frames
* Add support for cookies in HTTP client
* Add `:dns-resolver` option for all client protocols
* Add proxy support for HTTP client

### 0.4.4

Thanks to Antony Woods, Alexey Kachayev, Ivan Kryvoruchko, Yonatan Elhanan, Daniel Truemper

* fix memory lead in handling of binary websocket frames
* fix handling of decoder errors in Netty's HTTP stack

### 0.4.3

Thanks to Dominic Monroe

* fix bug in client ssl-context creation

### 0.4.2

Thanks to Zak Kristjanson, Elana Hashman, Casey Marshall, Jeroen van Dijk, Cameron Desautels, Leon Mergen, Ryan Waters, Nate Young, and Martin Klepsch

* allow for `:ssl-context` to be defined for clients
* add `aleph.netty/ssl-client-context`, and basic example of SSL configuration
* add websocket close status and message to stream description
* match nested query param behavior in clj-http
* target manifold 0.1.6
* make all threads daemon by default
* add `aleph.netty/wait-for-close` method, to prevent the process from closing prematurely

### 0.4.1

* altered shape of `manifold.stream/description` for Netty sources and sinks
* made instrumentation of individual connections optional, as it was having a small but measurable performance impact around ~100k connections
* target manifold 0.1.4, byte-streams 0.2.2

### 0.4.0

* from scratch rewrite, too many changes to name
