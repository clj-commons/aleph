(ns aleph.example.streaming
  (:use
    [clojure test]
    [aleph http formats]
    [lamina core]))

(defn stream-numbers [ch]
  (future
    (dotimes [i 100]
      (enqueue ch (str i "\n")))
    ;;(Thread/sleep 100) ;; track this down
    (close ch)))

(defn handler [request]
   (let [ch (channel)]
     (stream-numbers ch)
     {:status 200
      :headers {"content-type" "text/plain"}
      :body ch}))

(deftest test-streaming-example
  (let [server (start-http-server (wrap-ring-handler handler) {:port 8080})]
    (try
      (let [response (:body
                       (sync-http-request
                         {:method :get
                          :url "http://localhost:8080"}
                         ))]
        (is (= (range 100)
              (->> (channel-seq response -1)
                (map bytes->string)
                (map read-string)))))
      (finally
	(server)))))
