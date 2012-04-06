;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.netty
  (:use
    [potemkin])
  (:require
    [aleph.netty.core :as core]
    [aleph.netty.server :as server]
    [aleph.netty.client :as client]))

(import-fn core/channel-remote-host-address)
(import-fn core/channel-local-host-address)
(import-fn core/channel-local-port)

(import-fn server/start-server)
(import-fn server/server-message-handler)

(import-fn client/create-client)

(import-fn core/current-options)
(import-fn core/current-channel)

(import-macro core/create-netty-pipeline)


