(ns aleph.examples.http2
  (:require
    [aleph.http :as http]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs])
  (:import
    (java.util.zip
      GZIPInputStream
      InflaterInputStream)))

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
    "localhost"
    {:application-protocol-config (netty/application-protocol-config [:http2 :http1])}))


(def s (http/start-server hello-world-handler
                          {:port        443
                           :ssl-context http2-ssl-ctx}))

;; ## Clients

;; Like the server, the client must specify which HTTP versions are acceptable.
;; Since connections are typically shared/reused between requests, the standard
;; way to do this is to set {:connection-options {:http-versions [:http2 :http1]}}
;; when setting up a connection-pool. At the moment, Aleph's default connection
;; pool is still HTTP/1-only, so you must specify a custom connection pool. This
;; will change in a future version of Aleph.

;; ### Basic H2 client

(def conn-pool
  "Like with the ALPN config on the server, `:http-versions [:http2 :http1]`
   specifies both what protocols are acceptable, and the order of preference.
   Be warned, there is no guarantee a server will follow your preferred order,
   even if it supports multiple protocols.

   NB: `:insecure? true` is necessary for self-signed certs. Do not use in prod."
  (http/connection-pool {:connection-options {:http-versions [:http2 :http1]
                                              :insecure?     true}}))

@(http/get "https://localhost:443" {:pool conn-pool})

(.close s)



;; ### Compression

;; server
(def s (http/start-server hello-world-handler
                          {:port         443
                           :ssl-context  http2-ssl-ctx
                           :compression? true}))

;; client
;; TODO: add wrap-decompression middleware to handle this automatically
(let [acceptable-encodings #{"gzip" "deflate"}
      resp @(http/get "https://localhost:443"
                      {:pool    conn-pool
                       ;; See https://www.rfc-editor.org/rfc/rfc9110#section-12.5.3
                       :headers {"accept-encoding" "gzip;q=0.8, deflate;q=0.5, *;q=0"}})
      content-encoding (-> resp :headers (get "content-encoding"))
      stream-ctor (case content-encoding
                    "gzip" #(GZIPInputStream. %)
                    "deflate" #(InflaterInputStream. %)
                    ;; "br" #(BrotliInputStream. %) ; if you have brotli4j in your classpath
                    :unknown-encoding)]

  (if (contains? acceptable-encodings content-encoding)
    (do
      (println "Decompressing" content-encoding "response")
      (-> resp
          ;; remove/rename content-encoding header so nothing downstream tries to decompress again
          (update :headers #(clojure.set/rename-keys % {"content-encoding" "orig-content-encoding"}))
          :body
          (stream-ctor)
          bs/to-string))
    resp))
