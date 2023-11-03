(ns aleph.examples.http2
  (:require
    [aleph.http :as http]
    [aleph.netty :as netty])
  (:import
    (io.netty.handler.ssl
      ApplicationProtocolConfig
      ApplicationProtocolNames
      ApplicationProtocolConfig$Protocol
      ApplicationProtocolConfig$SelectorFailureBehavior
      ApplicationProtocolConfig$SelectedListenerFailureBehavior)))

;; This file assumes you've already covered basic HTTP client/server usage
;; in aleph.examples.http. This file covers just HTTP/2 additions.

;; ## Servers

;; By default, you don't usually start up an HTTP/2-only server. In most
;; scenarios, the server will support multiple HTTP versions, and you don't
;; know which you need until the client connects. Instead, you
;; start up a general HTTP server with support for Application-Layer Protocol
;; Negotiation (ALPN), which negotiates the HTTP version during the TLS
;; handshake. Unlike HTTP/1, HTTP/2 effectively requires TLS.

;; For Aleph, this means you need to provide a `SslContext` to the server
;; with ALPN configured, and HTTP/2 available as a protocol.

(defn hello-world-handler
  "A basic Ring handler which immediately returns 'hello world!'"
  [_]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    "hello world!"})

(def http2-ssl-ctx
  "This creates a self-signed certificate SslContext that supports both HTTP/2
   and HTTP/1.1. The protocol array lists which protocols are acceptable, and
   the order indicates the preference; in this example, HTTP/2 is preferred over
   HTTP/1 if the client supports both.

   NB: Do not use self-signed certs in production. See aleph.netty/ssl-server-context."
  (netty/self-signed-ssl-context
    {:application-protocol-config (netty/application-protocol-config [:http2 :http1])}))


(def s (http/start-server hello-world-handler
                          {:port        443
                           :ssl-context http2-ssl-ctx}))

;; ## Clients


