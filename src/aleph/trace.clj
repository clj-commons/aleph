;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace
  (:use
    [aleph redis]
    [lamina core]))

(defn- watch-value [client key options]
  (let [ch (channel)]
    (run-pipeline (redis-stream options)
      (fn [stream]
	(subscribe stream (str "aleph::broadcast::" key))
	(run-pipeline (client [:get (str "aleph::values::" (name key))])
	  #(do
	     (enqueue ch %)
	     (siphon (map* :message stream) ch)))))
    ch))

(defn- set-value [client key val]
  (client [:set (str "aleph::values::" key) val])
  (run-pipeline (client [:publish (str "aleph::broadcast::" (name key)) val])
    pos?))

(defn forward
  "Forwards "
  [options]
  (let [client (redis-client options)]
    ))
     
