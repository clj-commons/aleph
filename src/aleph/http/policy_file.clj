;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.policy-file
  (:use
    [aleph netty]
    [clojure.contrib.prxml])
  (:import
    [org.jboss.netty.buffer
     ChannelBuffer
     ChannelBuffers]
    [org.jboss.netty.channel
     Channel
     ChannelFutureListener]
    [org.jboss.netty.util
     CharsetUtil]))

(defn- to-xml [s]
  (with-out-str
    (prxml s)))

(def request
  (ChannelBuffers/copiedBuffer
    (to-xml [:policy-file-request])
    CharsetUtil/US_ASCII))

(defn response [options]
  (ChannelBuffers/copiedBuffer
    (let [policies :permitted-cross-domain-policies
	  options (merge
		    {:allow-access-from "*"
		     :to-ports "*"
		     policies "master-only"}
		    options)]
      (with-out-str
	(prxml
	  [:decl! {:version "1.0" :encoding "US-ASCII"}]
	  [:doctype! "cross-policy-domain SYSTEM \"http://www.macromedia.com/xml/dtds/cross-domain-policy.dtd\""]
	  [:site-control
	   {policies (policies options)}]
	  [:cross-domain-policy
	   (dissoc options :permitted-cross-domain-policies)])))
    CharsetUtil/US_ASCII))

(defn create-pipeline [options]
  (let [rsp (response options)]
    (create-netty-pipeline
      :request-accumulator
      (message-stage
	(fn [^Channel ch ^ChannelBuffer buf]
	  (cond
	    (= request buf)
	    (-> ch (.write rsp) (.addListener ChannelFutureListener/CLOSE))
	    (> (.readableBytes request) (.readableBytes buf))
	    (.close ch)
	    :else
	    nil))))))
      
(defn start-policy-file-server
  ([]
     (start-policy-file-server nil))
  ([options]
     (start-server
       #(create-pipeline options)
       {:port 843})))
