(ns ^:no-doc aleph.http.schema
  (:require [malli.core :as m]))

(def server-port [:maybe :int])
(def server-name [:maybe :string])
(def remote-addr [:maybe :string])
(def uri [:maybe :string])
(def query-string [:maybe :string])
(def scheme [:enum :http :https])
(def request-method [:or :string :keyword])
(def content-type [:maybe [:or :string :keyword]])
(def content-length [:maybe :int])
(def character-encoding [:maybe :string])

(def ring-request [:map
                   [:request-method request-method]
                   [:server-port {:optional true} server-port]
                   [:server-name {:optional true} server-name]
                   [:remote-addr {:optional true} remote-addr]
                   [:uri {:optional true} uri]
                   [:query-string {:optional true} query-string]
                   [:scheme {:optional true} scheme]
                   [:content-type {:optional true} content-type]
                   [:content-length {:optional true} content-length]
                   [:character-encoding {:optional true} character-encoding]
                   [:body {:optional true} :any]])

(def valid-request? (m/validator ring-request))
(def explain-request (m/explainer ring-request))





(comment

  (map #(m/validate server-port %) ["bingo" nil 123])

  (m/validate ring-request
              {:server-port 80
               :server-name "www.example.com"
               :remote-addr "127.0.0.1"
               :uri "/blah/blah"
               :query-string "?foo=bar"
               :scheme :https
               :request-method :get})
  )
