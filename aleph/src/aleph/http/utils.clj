;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.utils
  (:require
    [clj-http.client :as client]
    [clojure.string :as str]))

(defn to-str [x]
  (if (keyword? x)
    (name x)
    (str x)))

(defn- string->hash [s outer-separator inner-separator]
  (when s
    (apply hash-map
      (apply concat
	(map
	  #(let [pair (.split % inner-separator)]
	     (list (first pair) (or (second pair) "")))
	  (.split s outer-separator))))))

(defn- cookie->hash [cookie]
  (string->hash cookie "[;]" "[=]"))

(defn- hash->cookie [cookie]
  (when cookie
    (if (map? cookie)
      (->> cookie
	(map #(str (to-str (first %)) "=" (second %)))
	(interpose ";")
	(apply str))
      cookie)))

(defn lowercase-headers
  [msg]
  (update-in msg [:headers]
    #(zipmap (map str/lower-case (keys %)) (vals %))))

(defn- wrap-request-cookie [request]
  (if-let [cookie (:cookies request)]
    (assoc-in request [:headers "cookie"] cookie)
    request))

(defn- wrap-response-cookie [response]
  (if-let [cookie (:cookies response)]
    (assoc-in response [:headers "set-cookie"] cookie)
    response))

(defn- wrap-keep-alive [request]
  (update-in request [:headers "connection"]
    #(or %
       (if (false? (:keep-alive? request))
	 "close"
	 "keep-alive"))))

(defn wrap-request [request]
  (-> request
    lowercase-headers
    wrap-request-cookie
    wrap-keep-alive
    ))

(defn wrap-client-request [request]
  ((comp
     wrap-request
     (-> identity
       client/wrap-input-coercion
       client/wrap-content-type
       client/wrap-accept
       client/wrap-query-params
       client/wrap-basic-auth
       client/wrap-method
       client/wrap-url))
   request))

(defn wrap-response [response]
  (-> response
    wrap-response-cookie))

