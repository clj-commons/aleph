(ns aleph.benchmark.hello-world
  (:use
    [aleph http]
    [lamina core]))

(def counter (atom 0))

#_(def stop-server
  (start-http-server
    (wrap-ring-handler
      (fn [request]
	{:status 200
	 :content-type "text/plain"
	 :body (str (swap! counter inc))}))
    {:port 8080}))
