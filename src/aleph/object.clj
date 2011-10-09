;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.object
  (:use 
    [aleph netty]
    [lamina.core])
  (:require [aleph.tcp :as tcp])
  (:import
    [org.jboss.netty.channel
     ChannelPipeline]
    [org.jboss.netty.handler.codec.serialization
     ObjectEncoder
     ObjectDecoder]))

(defn- server-pipeline [handler options]
  (let [pipeline ^ChannelPipeline (tcp/basic-server-pipeline handler identity identity options)]
    (.addFirst pipeline "encoder" (ObjectEncoder.))
    (.addFirst pipeline "decoder" (ObjectDecoder.))
    pipeline))

(defn- client-pipeline [ch options]
  (let [pipeline ^ChannelPipeline (tcp/basic-client-pipeline ch identity options)]
    (.addFirst pipeline "encoder" (ObjectEncoder.))
    (.addFirst pipeline "decoder" (ObjectDecoder.))
    pipeline))

(defn start-object-server
  "Identical to start-tcp-server, except that the channel accepts any serializable Java
   object, including the standard Clojure data structures."
  [handler options]
  (let [options (merge
		  {:name (str "object-server:" (:port options))}
		  options)]
    (start-server
      #(server-pipeline
	 handler
	 %)
      options)))

(defn object-client
  "Identical to tcp-client, except that the channel accepts any serializable Java object,
   including the standard Clojure data structures."
  [options]
  (let [options (merge
		  {:name (str "object-client:" (:host options) ":" (:port options) ":" (gensym ""))}
		  options)]
    (create-client
      #(client-pipeline % options)
      identity
      options)))


