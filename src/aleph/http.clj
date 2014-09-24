(ns aleph.http
  (:require [aleph.http.server :as server]))

(defn start-server
  [handler
   {:keys [port executor raw-stream? bootstrap-transform ssl-context]
    :as options}]
  (server/start-server handler options))
