;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.netty
  (:use
    [potemkin])
  (:require
    [aleph.netty :as netty]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpMessage
     HttpMethod
     HttpRequest
     HttpChunk
     HttpHeaders
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel]
    [java.net
     InetAddress
     InetSocketAddress]))

(def request-methods [:get :post :put :delete :trace :connect :head :options :patch])

(def netty-method->keyword
  (zipmap
    (map #(HttpMethod/valueOf (name %)) request-methods)
    request-methods))

(defn request-method [^HttpRequest request]
  (netty-method->keyword (.getMethod request)))

(defn http-headers [^HttpMessage msg]
  (let [k (keys (.getHeaders msg))]
    (zipmap
      (map str/lower-case k)
      (map #(.getHeader msg %) k))))

(defn http-content-type [^HttpMessage msg]
  (.getHeader msg "Content-Type"))

(defn http-character-encoding [^HttpMessage msg]
  (when-let [content-type (.getHeader msg "Content-Type")]
    (->> (str/split content-type #"[;=]")
      (map str/trim)
      (drop-while #(not= % "charset"))
      second)))

(defn http-content-length [^HttpMessage msg]
  (when-let [content-length (.getHeader msg "Content-Length")]
    (try
      (Integer/parseInt content-length)
      (catch Exception e
        (log/error e (str "Error parsing content-length: " content-length))
        nil))))

(defn request-uri [^HttpRequest request]
  (first (str/split (.getUri request) #"[?]")))

(defn request-query-string [^HttpRequest request]
  (second (str/split (.getUri request) #"[?]")))

(defn channel-remote-host-address [^Channel channel]
  (when-let [socket-address (.getRemoteAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-host-address [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-port [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (.getPort ^InetSocketAddress socket-address)))

;;;

(def-custom-map LazyMap
  :get
  (fn [_ data _ key default-value]
    `(if-not (contains? ~data ~key)
       ~default-value
       (let [val# (get ~data ~key)]
         (if (delay? val#)
           @val#
           val#)))))

(defn lazy-map [& {:as m}]
  (LazyMap. m))

(defn netty-request->map [^HttpRequest netty-request]
  (let [netty-channel (netty/current-channel)
        request (lazy-map
                  :scheme :http
                  :remote-addr (delay (channel-remote-host-address channel))
                  :server-name (delay (channel-local-host-address channel))
                  :server-port (delay (channel-local-port channel))
                  :request-method (delay (request-method netty-request))
                  :headers (delay (http-headers netty-request))
                  :content-type (delay (http-content-type netty-request))
                  :character-encoding (delay (http-character-encoding netty-request))
                  :uri (delay (request-uri netty-request))
                  :query-string (delay (request-query-string netty-request))
                  :content-length (delay (http-content-length netty-request)))]
    (assoc-request-body request netty-request)))



(defn netty-request->map [^HttpRequest ])
