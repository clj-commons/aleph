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
    [aleph.netty.core :only (local-options)]
    [lamina core api connections trace executor])
  (:require
    [aleph.http.websocket :as ws]
    [aleph.http.client-middleware :as middleware]
    [aleph.netty.client :as client]
    [aleph.netty :as netty]
    [aleph.formats :as formats]
    [aleph.http.core :as http]
    [aleph.http.options :as options]
    [clojure.tools.logging :as log])
  (:import
    [java.util.concurrent
     TimeoutException]
    [org.jboss.netty.handler.codec.http
     HttpHeaders
     DefaultHttpChunk
     HttpChunk
     HttpMessage
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor
     HttpContentDecompressor
     HttpClientCodec]
    [java.nio.channels
     ClosedChannelException]))

;;;

(defn wrap-http-server-channel [options ch]
  (let [responses (channel)
        auto-decode? (options/auto-decode?)]

    ;; transform responses
    (join
      (http/expand-writes http/ring-map->netty-response true responses)
      ch)

    ;; transform requests
    (let [requests (->> ch
                     http/collapse-reads
                     (map* http/netty-request->ring-map))
          requests (if auto-decode?
                     (map* http/decode-message requests)
                     requests)]
          (splice requests responses))))

(defn start-http-server [handler options]
  (let [server-name (or
                      (:name options)
                      (-> options :server :name)
                      "http-server")
        netty-options (-> options :netty)
        error-probe (error-probe-channel [server-name :error])
        channel-handler (server-generator
                          (fn [ch req]

                            ;; set local options
                            (.set local-options options)

                            (let [ch* (result-channel)]

                              ;; run the handler
                              (run-pipeline (dissoc req :keep-alive?)
                                {:error-handler #(error ch* %)}
                                #(handler ch* %))

                              ;; handle the response
                              (run-pipeline ch*
                                {:error-handler (fn [_])}
                                #(enqueue ch (assoc % :keep-alive? (:keep-alive? req))))))
                          
                          (merge
                            {:error-response (fn [ex]
                                               (enqueue error-probe ex)
                                               (if (or
                                                     (= ex :lamina/timeout!)
                                                     (instance? TimeoutException ex))
                                                 {:status 408}
                                                 {:status 500}))}
                            (:server options)
                            {:name server-name}))]
    (netty/start-server
      server-name
      (fn [channel-group]
        (let [pipeline (netty/create-netty-pipeline server-name true channel-group
                         :decoder (HttpRequestDecoder.
                                    (get netty-options "http.maxInitialLineLength" 16384)
                                    (get netty-options "http.maxHeaderSize" 16384)
                                    (get netty-options "http.maxChunkSize" 16384))
                         :encoder (HttpResponseEncoder.)
                         :deflater (HttpContentCompressor.)
                         :handler (server-message-handler
                                    (fn [ch _]
                                      (->> ch
                                        (wrap-http-server-channel options)
                                        channel-handler))))]
          (when (options/websocket? options)
            (.addBefore pipeline "handler" "websocket"
              (ws/server-handshake-stage handler)))
          ((get netty-options "pipeline-transform" identity) pipeline)
          pipeline))
      options)))

;;;

(defn wrap-http-client-channel [options ch]
  (let [requests (channel)
        options (client/expand-client-options options)
        auto-decode? (options/auto-decode? options)]

    ;; transform requests
    (join
      (->> requests
        (map*
          (fn [req]
            (let [req (client/expand-client-options req)
                  req (merge options req)
                  req (middleware/transform-request req)]
              (update-in req [:keep-alive?] #(if (nil? %) true %)))))
        (http/expand-writes http/ring-map->netty-request false))
      ch)

    ;; transform responses
    (let [responses (->> ch
                      http/collapse-reads
                      (map* http/netty-response->ring-map)
                      (map* #(dissoc % :keep-alive?)))
          responses (if auto-decode?
                      (map* http/decode-message responses)
                      responses)
          responses (if-let [frame (formats/options->decoder options)]
                      (map*
                        (fn [rsp]
                          (update-in rsp [:body]
                            #(let [body (if (channel? %)
                                          %
                                          (closed-channel %))]
                               (formats/decode-channel frame body))))
                        responses)
                      responses)]
      (splice responses requests))))

(defn http-connection [options]
  (let [client-name (or
                      (:name options)
                      (-> options :client :name)
                      "http-client")]
    (run-pipeline nil
      {:error-handler (fn [_])}
      (fn [_]
        (create-client
          client-name
          (fn [channel-group]
            (create-netty-pipeline client-name false channel-group
              :codec (HttpClientCodec.)
              :inflater (HttpContentDecompressor.)))
          options))
      (fn [connection]
        (let [ch (wrap-http-client-channel options connection)]
          ch)))))

(defn http-client [options]
  (client #(http-connection options)))

(defn pipelined-http-client [options]
  (pipelined-client #(http-connection options)))

(defn-instrumented http-request-
  {:name "aleph:http-request"}
  [request timeout]
  (let [request (assoc request :keep-alive? false)
        start (System/currentTimeMillis)]
    (run-pipeline request
      {:error-handler (fn [_])}
      http-connection
      (fn [ch]
        (let [elapsed (- (System/currentTimeMillis) start)]
          (run-pipeline ch
            {:timeout (when timeout (- timeout elapsed))
             :error-handler (fn [ex] (close ch))}
            (fn [ch]
              (enqueue ch request)
              (read-channel ch))
            (fn [rsp]
              (if (channel? (:body rsp))
                (on-closed (:body rsp) #(close ch))
                (close ch))
              rsp)))))))

(defn http-request
  ([request]
     (http-request- request nil))
  ([request timeout]
     (http-request- request timeout)))
