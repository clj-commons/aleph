;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.example.twitter
  (:use
    [lamina.core]
    [aleph http formats]))

;; NOTE: Twitter's moved over to OAuth for most streams.  This example still works,
;; but it won't work universally. 

(def username "aleph_example")
(def password "_password")

(defn sample-stream []
  (let [req (sync-http-request
	      {:method :get
	       :basic-auth [username password]
	       :url "http://stream.twitter.com/1/statuses/sample.json"})]
    (Thread/sleep 500)
    (enqueue (:body req) nil)
    req))

