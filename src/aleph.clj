;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph
  (:require
    [aleph.http :as http]
    [aleph.core :as core]))

(defn run-aleph [handler options]
  (let [port (:port options)]
    (core/start-server port #(http/http-pipeline port handler))))

(defn respond! [request msg]
  (io!
    ((:respond request)
     request
     (http/transform-response request msg))))
