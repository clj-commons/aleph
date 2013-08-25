;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.options
  (:use [aleph.netty :only (current-options)]))

(defn auto-decode?
  ([]
     (auto-decode? (current-options)))
  ([options]
     (boolean (or (:auto-decode? options) (:auto-transform options) (:auto-transform? options)))))

(defn auto-encode?
  ([]
     (auto-encode? (current-options)))
  ([options]
     (boolean (or (:auto-encode? options) (:auto-transform options) (:auto-transform? options)))))

(defn charset
  ([]
     (charset (current-options)))
  ([options]
     (or (:charset options) "utf-8")))

(defn channel-ring-requests?
  ([request]
     (channel-ring-requests? (current-options) request))
  ([options request]
     (let [channel-requests? (:channel-ring-requests? options)]
       (if (ifn? channel-requests?)
         (channel-requests? request)
         channel-requests?))))

(defn websocket?
  ([]
     (websocket? (current-options)))
  ([options]
     (boolean (or (:websocket options) (:websocket? options)))))

(defn websocket-handshake-handler
  ([]
     (websocket-handshake-handler (current-options)))
  ([options]
     (:websocket-handshake-handler options)))

(defn executor
  ([]
     (executor (current-options)))
  ([options]
     (-> options :server :executor)))
