(ns aleph.benchmark.redis
  (:use aleph.redis))

#_(def client (redis-client {:host "localhost"}))

#_(future
  (loop []
    (client [:set :a :b])
    @(client [:get :a])
    (recur)))
