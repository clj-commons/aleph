;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.client-middleware
  (:use
    [clojure.walk])
  (:require
    [clojure.string :as str]
    [aleph.formats :as formats]))

;; adapted from clj-http, which uses the MIT license and is amenable
;; to this sort of copy/pastery

(defn generate-query-string [params]
  (str/join "&"
    (mapcat (fn [[k v]]
              (if (sequential? v)
                (map #(str (formats/url-encode (name %1))
                        "="
                        (formats/url-encode (str %2)))
                  (repeat k) v)
                [(str (formats/url-encode (name k))
                   "="
                   (formats/url-encode (str v)))]))
      params)))

(defn wrap-query-params [client]
  (fn [{:keys [query-params] :as req}]
    (if query-params
      (client (-> req
                (dissoc :query-params)
                (assoc :query-string (generate-query-string query-params))))
      (client req))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (formats/base64-encode basic-auth))))

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req
                (dissoc :basic-auth)
                (assoc-in [:headers "Authorization"]
                  (basic-auth-value basic-auth))))
      (client req))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req
                (dissoc :method)
                (assoc :request-method m)))
      (client req))))

(defn wrap-form-params [client]
  (fn [{:keys [form-params content-type request-method auto-encode?]
        :or {content-type "application/x-www-form-urlencoded"}
        :as req}]
    (if (and form-params (#{:post :put} request-method))
      (client (-> req
                (dissoc :form-params)
                (assoc
                  :content-type content-type
                  :body (if (and (= content-type "application/json") auto-encode?)
                          (formats/encode-json->bytes form-params)
                          (generate-query-string form-params)))))
      (client req))))

(defn- nest-params
  [request param-key]
  (if-let [params (request param-key)]
    (assoc request param-key
      (prewalk
        #(if (and (vector? %) (map? (second %)))
           (let [[fk m] %]
             (reduce
               (fn [m [sk v]]
                 (assoc m (str (name fk) \[ (name sk) \]) v))
               {}
               m))
           %)
        params))
    request))

(defn wrap-nested-params
  [client]
  (fn [{:keys [query-params form-params] :as req}]
    (client
      (reduce
        nest-params
        req
        [:query-params :form-params]))))

;;; end clj-http

(def transform-request
  (-> identity
    wrap-form-params
    wrap-method
    wrap-basic-auth
    wrap-user-info
    wrap-query-params
    wrap-nested-params))


