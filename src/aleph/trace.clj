;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace
  (:use
    [potemkin])
  (:require
    [aleph.stomp.router :as stomp-router]
    [aleph.trace.router :as router]
    [aleph.stomp :as stomp]))

(defn start-trace-router [options]
  (stomp/start-stomp-router
    (merge
      {:name "trace-server"}
      router/router-options
      options)))

(defn trace-endpoint [options]
  (stomp/stomp-endpoint
    (merge
      {:name "trace-endpoint"}
      (router/endpoint-options (get options :aggregation-period 1000))
      options)))

(import-fn stomp/subscribe)
