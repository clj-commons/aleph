(ns aleph.benchmark.redis
  (:use aleph.redis))

(def client (redis-client {:host "localhost"}))

#_(future
  (loop []
    (client [:set :a :b])
    @(client [:get :a])
    (recur)))
