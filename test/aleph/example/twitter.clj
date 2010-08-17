;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.example.twitter
  (:use [aleph http core formats]))

(def username "aleph_example")
(def password "_password")

(defn sample-stream []
  (let [ch (wait-for-pipeline
	     (http-request
	       {:request-method :get
		:headers {"authorization" (str "basic " (base64-encode (str username ":" password)))}
		:url "http://stream.twitter.com/1/statuses/sample.json"}))]
    (doall (map :body (channel-seq ch 1000)))))

