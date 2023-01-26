(ns aleph.utils
  (:import java.net.ServerSocket))

(defn rand-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))
