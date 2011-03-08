(ns aleph.example.http-tcp
  (:use
    aleph.http
    aleph.tcp
    lamina.core
    gloss.core
    gloss.io))

(defn tcp-over-http-client [options]
  (let [upstream-channel (channel)
	response-result (http-request (merge options {:method :put, :body upstream-channel}))]
    (run-pipeline response-result 
      (fn [response]
	(let [downstream-channel (:body response)
	      downstream-channel (if-let [frame (:frame options)]
				   (decode-channel downstream-channel frame)
				   downstream-channel)]
	  (splice downstream-channel upstream-channel))))))

(defn tcp-over-http-server [tcp-endpoint]
  )
