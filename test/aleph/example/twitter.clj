;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.example.twitter
  (:use
    [lamina core]
    [aleph http formats]))

;; NOTE: Twitter's moved over to OAuth for most streams.  This example still works,
;; but it won't work universally. 

(def username "aleph_example")
(def password "_password")

(defn sample-stream
  ([]
     (sample-stream 500))
  ([duration]
     (let [req (sync-http-request
		 {:method :get
		  :basic-auth [username password]
		  :url "http://stream.twitter.com/1/statuses/sample.json"
		  :auto-transform true})]
       (Thread/sleep duration)
       (close (:body req))
       req)))

(defn twitter-proxy-handler [ch request]
  ;; since we use the same format for our responses and the ones we receive from
  ;; requests, we can simply pass Twitter's response along to our user
  (async
    (enqueue ch
      (http-request 
	{:method :get 
	 :basic-auth ["aleph_example" "_password"]
	 :url "http://stream.twitter.com/1/statuses/sample.json"}))))

(defn init-stream-channel [ch]
  ;; we need a sink for messages, since we always fork the original channel
  (receive-all ch (fn [_] ))
  ;; take the body of the response, and siphon it into the channel
  (async
    (let [response (http-request 
		     {:method :get 
		      :basic-auth ["aleph_example" "_password"]
		      :url "http://stream.twitter.com/1/statuses/sample.json"})]
      (siphon (:body response) ch))))

(defn twitter-broadcast-handler [ch request]
  ;; get the shared broadcast channel, and use a forked version of it as
  ;; the body of our response
  (let [twitter-stream (named-channel :twitter-stream init-stream-channel)]
    (enqueue ch
      {:status 200
       :headers {"content-type" "application/json"}
       :body (fork twitter-stream)})))

(defn request-handler [ch request]
  (condp = (:uri request)
    "/proxy" (twitter-proxy-handler ch request)
    "/broadcast" (twitter-broadcast-handler ch request)))

(defn start-twitter-test
  ([]
     (start-twitter-test 10))
  ([run-duration-in-seconds]
     (let [stop-server (start-http-server request-handler {:port 8080})]
       (try
	 (Thread/sleep (* 1000 run-duration-in-seconds))
	 (finally
	   (release-named-channel :twitter-stream)
	   (stop-server))))))
