### Unreleased

* Loosen `wrap-validation` validation to support strings in :request-method. (See release in 0.6.2 for more information). 

### 0.7.1

* Bump Manifold to 0.4.2 to fix Promesa print-method hierarchy bug
* Bump Dirigiste to 1.0.4
* Log SSL handshake completion at debug level instead of info level
  
Contributions by Matthew Davidson and Eric Dvorsak

### 0.7.0

* HTTP/2 server support, including ALPN and h2c
* HTTP/2 client support
* Support more compression codecs (Brotli, Zstd, and Snappy)
* Enable SSL/TLS certificate hostname verification by default in the client for 
  security purposes. (Warning: This is a BREAKING change for those with 
  misconfigured server certificates.)
* Bump Manifold dep to 0.4.1, which means Aleph/Manifold deferreds now support 
  CompletionStage for better Java interop
* Bump Netty to 4.1.00.Final
* Improved error logging

Contributions by Matthew Davidson, Moritz Heidkamp, and Arnaud Geiser. 

Manifold contributions by Renan Ribeiro, Ryan Smith, Arnaud Geiser, and Matthew 
Davidson.

### 0.6.3

* Bump Netty to 4.1.94.Final for CVE-2023-34462

Contributions by Matthew Davidson and Stefan van den Oord.

### 0.6.2

* Fix backwards-compatibility for transport options
* Bump Netty to 4.1.89.Final, and io_uring to 0.0.18.Final
* Bump deps and example deps
* Upgrade CircleCI instance size
* Switch to pedantic deps for CircleCI

### Breaking changes

* Add `wrap-validation` middleware to validate Ring maps [#679](https://github.com/clj-commons/aleph/pull/679). This adds a stricter interpretation of the ring spec, which may fail on previously valid input. For example, strings (e.g. `"GET"`) and keywords (e.g. `:get`) were both accepted values for `:request-method`, but now only keywords are accepted. This will be fixed in the release after 0.7.1.

Contributions by Arnaud Geiser, Ertuğrul Çetin, Jeroen van Dijk, David Ongaro, 
Matthew Davidson, and Moritz Heidkamp.

### 0.6.1

* Switch back to JDK 8 in CI
* Fix SSL handshake test for JDK 8
* Stop linking to old aleph.io site, in favor of examples and cljdoc articles
* Remove unnecessary use of `bound-fn*` for `operation-complete` 
* Support MIME types and in-memory files when encoding uploads
* Support kqueue and io_uring
* Allow manual cleanup of uploaded files and document it
* Add `validate-headers`, `initial-buffer-size`, `allow-duplicate-content-lengths`, and `max-request-body-size` server options
* Add reader backpressure tests
* Add charset to `text/plain` responses
* Randomize TCP and UDP test ports
* Document internal scheduler for high-performance timing
* Improve error messages on non-compliant Ring maps
* Handle edge cases with decoder failures and quicker error propagation to caller
* Support file names with in-memory file uploads
* Bump Manifold to 0.3.0
* Bump Dirigiste to 1.0.3
* Bump Netty to 4.1.87.Final

Contributions by (in alphabetical order):

Matthew Davidson, Arnaud Geiser, Moritz Heidkamp, Eugene Pakhomov, and pbwolf.

Extra big thanks to Arnaud, who tackled the vast majority of these improvements!


### 0.6.0

* Add initial kondo support
* Upgrade to Netty 4.1.85.Final
* Add options to configure graceful shutdown timeout
* Switch from custom deps.edn script to `lein2deps`
* Upgrade CircleCI Docker images
* Move bouncycastle to dev profile for REPL testing
* Add CONTRIBUTING doc for newcomers
* Add support for deps.edn/tools.deps
* Improve `wrap-future` short-circuiting behavior of realized error futures
* Fix bug treating a `ClosedChannelException` during an SSL/TLS handshake as a success deferred
* Support replacing Dirigiste connection pools with custom pools
* Improve options for setting up SSL/TLS. Now accepts a map for options, and `javax.net.ssl.TrustManager`. See `ssl-server-context`.
* Support custom error handlers
* Support HttpObjectAggregator on the Netty pipeline
* Log less info on SSL/TLS handshake failure
* BREAKING: If epoll is missing, setting `:epoll? true` will now be an error instead of automatically downgrading. To get the old behavior, pass `:epoll? (aleph.netty/epoll-available?)`.
* Make TCP+SSL server call handler only after successful SSL handshake, and improve SSL errors
* Clarified `idle-timeout` docstring

Contributions by (in alphabetical order):

Anthony Bondarenko, Michiel Borkent, Matthew Davidson, 
Balint Erdos, Arnaud Geiser, Moritz Heidkamp, Alexey Kachayev, 
Pierre-Yves Ritschard, Andrew Rudenko, saguywalker, 
and Alexander Yakushev 

### 0.5.0

* Add initial clj-kondo hooks
* Minor bugfix in examples code
* Add pipeline-transform test
* Add missing type hint in websocket-server-handler
* Correctly handle too large headers/URIs
* Add doc for undocumented response-executor parameter
* Minor bugfix for `keep-alive? false`
* Fixed major memory leak when sending InputStreams
* Fixed bug when sending empty files
* Fix returned filename in multipart uploads for clj-http parity
* Ensure client exceptions handled and channel closed on invalid input
* Fix minor bug with redirects and `:method` key
* Fix bug in idle state timeouts
* Disable misleading logs during tests
* Switch to Netty's `FastThreadLocalThread` for threads
* Ensure `wrap-future` callbacks have thread bindings and a full classloader chain
* Fix minor reflection warning in HttpContentCompressor ctor
* Add missing direct dependency on Dirigiste

Contributions by Arnaud Geiser, Moritz Heidkamp, Erik Assum, Ivar Refsdal, and Matthew Davidson

### 0.4.7

Contributions by (in alphabetical order):

Erik Assum, Yoan Blanc, Reynald Borer, Michael Cameron, Jonathan Chen, Daniel Compton, 
Matthew Davidson, Rafal Dittwald, Ganesh Gautam, Aaron Muir Hamilton, Moritz Heidkamp, 
Alexey Kachayev, Dominic Pearson, Matthew Phillips, Denis Shilov, Zach Tellman, 
Mark Wardle, and Alexander Yakushev

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
