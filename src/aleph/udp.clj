;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software. 

(ns aleph.udp
  (:require
    [aleph.netty :as netty])
  (:import
    [org.jboss.netty.handler.codec.serialization
     ObjectEncoder
     ObjectDecoder]))

(defn udp-socket
  "Returns a result-channel that emits a channel if it successfully opens
  a UDP socket.  Send messages by enqueuing maps containing:

  {:host :port :message}

  and if bound to a port you can listen by receiving equivalent messages on
  the channel returned.

  Optional parameters include:
    :frame          ; a Gloss frame for encoding and decoding UDP packets
    :decoder        ; a Gloss frame for decoding packets - overrides :frame
    :encoder        ; a Gloss frame for encoding packets - overrides :frame
    :port <int>     ; to listen on a specific local port and
    :broadcast true ; to broadcast from this socket
    :buf-size <int> ; to set the receive buffer size
  "
  ([]
     (udp-socket nil))
  ([options]
     (let [name (or (:name options) "udp-socket")]
       (netty/create-udp-socket
         name
         (fn [_] (netty/create-netty-pipeline name nil nil))
         (assoc options :auto-encode? true)))))

(defn udp-object-socket
  ([]
     (udp-object-socket nil))
  ([options]
     (let [name (or (:name options) "udp-object-socket")]
       (netty/create-udp-socket
         name
         (fn [_]
           (netty/create-netty-pipeline name false nil
             :encoder (ObjectEncoder.)
             :decoder (ObjectDecoder.)))
         (assoc options :auto-encode? false)))))

