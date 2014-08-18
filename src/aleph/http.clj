(ns aleph.http
  (:require
    [aleph.http.server :as server]))

(defn start-server
  [handler options]
  (server/start-server handler options))
