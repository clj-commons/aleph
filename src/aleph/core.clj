;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:author "Zachary Tellman"
    :doc "An event-driven server."}
  aleph.core
  (:use
    [clojure.contrib.def :only (defmacro-)])
  (:require
    [aleph.http :as http]
    [aleph.netty :as netty]
    [aleph.core.pipeline :as pipeline]
    [aleph.core.channel :as channel])) 

(defmacro- import-fn [sym]
  (let [m (meta (eval sym))
        m (meta (intern (:ns m) (:name m)))
        n (:name m)
        arglists (:arglists m)
        doc (:doc m)]
    (list `def (with-meta n {:doc doc :arglists (list 'quote arglists)}) (eval sym))))

;;;

(import-fn pipeline/pipeline)
(import-fn pipeline/blocking)

(import-fn #'channel/receive)
(import-fn #'channel/receive-all)
(import-fn #'channel/cancel-callback)
(import-fn #'channel/enqueue)
(import-fn #'channel/enqueue-and-close)
(import-fn #'channel/closed?)
(import-fn channel/poll)
(import-fn channel/channel-pair)
(import-fn channel/channel)
(import-fn channel/constant-channel)

(import-fn pipeline/redirect)
(import-fn pipeline/restart)

(defn on-success
  "Adds a callback to a pipeline channel which will be called if the
   pipeline succeeds.

   The function will be called with (f result)"
  [ch f]
  (receive (:success ch) f))

(defn on-error
  "Adds a callback to a pipeline channel which will be called if the
   pipeline terminates due to an error.

   The function will be called with (f intermediate-result exception)."
  [ch f]
  (receive (:error ch) (fn [[result exception]] (f result exception))))

;;;

(defn start-server
  "Starts a server.  The options hash should contain a value for :protocol and :port.

   Currently :http is the only supported protocol."
  [handler options]
  (let [port (:port options)
	protocol (:protocol options)]
    (netty/start-server
      (case protocol
	:http #(http/server-pipeline handler options))
      port)))

(defn client
  "Creates a client.  The options hash must contain a value for :protocol, :host, and :port.

   Currently :http is the only supported protocol."
  [options]
  (let [port (:port options)
	host (:host options)
	protocol (:protocol options)]
    (netty/create-client
      (case protocol
	:http #(http/client-pipeline options))
      host
      port)))

(defn start-http-server
  "Starts an HTTP server."
  [handler options]
  (start-server handler
    (assoc options
      :protocol :http
      :error-handler (fn [^Throwable e] (.printStackTrace e)))))

(defn http-client
  "Create an HTTP client."
  [options]
  (client (assoc options :protocol :http)))

(defn stop
  "Stops a server."
  [^org.jboss.netty.channel.Channel server]
  (.close server))

