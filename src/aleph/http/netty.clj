;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.netty
  (:use
    [aleph.http.core]
    [aleph netty formats]
    [lamina core api connections])
  (:require
    [aleph.netty :as netty]
    [aleph.formats :as formats]
    [aleph.http.core :as http])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpHeaders
     DefaultHttpChunk
     HttpChunk
     HttpMessage
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [java.nio.channels
     ClosedChannelException]))

;;;

(defn wrap-http-server-channel [options ch]
  (let [ch* (channel)]
    (join
      (http/expand-writes http/ring-map->netty-response ch*)
      ch)
    (splice
      (->> ch
        http/collapse-reads
        (map* http/netty-request->ring-map))
      ch*)))

(defn start-http-server [handler options]
  (let [server-name (or
                      (:name options)
                      (-> options :server :name)
                      "http-server")
        error-predicate (or
                          (:error-predicate options)
                          #(not (instance? ClosedChannelException %)))
        netty-options (-> options :netty)
        channel-handler (server-generator
                          (fn [ch req]
                            (run-pipeline (dissoc req :keep-alive?)
                              #(let [ch* (result-channel)]
                                 (handler ch* %)
                                 ch*)
                              #(enqueue ch
                                 (assoc % :keep-alive? (:keep-alive? req)))))
                          (:server options))]
    (netty/start-server
      server-name
      (fn [channel-group]
        (netty/create-netty-pipeline server-name error-predicate channel-group
          :decoder (HttpRequestDecoder.
		     (get netty-options "http.maxInitialLineLength" 8192)
		     (get netty-options "http.maxHeaderSize" 16384)
		     (get netty-options "http.maxChunkSize" 16384))
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
          :handler (server-message-handler
                     (fn [ch _]
                       (->> ch
                         (wrap-http-server-channel options)
                         channel-handler)))))
      options)))
