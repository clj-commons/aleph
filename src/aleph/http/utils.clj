;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.http.utils
  (:use
    [lamina core]
    [aleph formats])
  (:require
    [clj-http.client :as client]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    [org.jboss.netty.handler.codec.http
     HttpMessage
     HttpMethod
     HttpRequest
     HttpChunk
     HttpHeaders
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel]
    [java.net
     InetAddress
     InetSocketAddress]
    [nl.bitwalker.useragentutils
     UserAgent]))

;;;

(def request-methods [:get :post :put :delete :trace :connect :head :options :patch])

(def request-method->keyword
  (zipmap
    (map #(HttpMethod/valueOf (name %)) request-methods)
    request-methods))

(def keyword->request-method
  (zipmap
    (vals request-method->keyword)
    (keys request-method->keyword)))

(defn request-method [^HttpRequest request]
  (request-method->keyword (.getMethod request)))

(defn http-headers [^HttpMessage msg]
  (let [k (keys (.getHeaders msg))]
    (zipmap
      (map str/lower-case k)
      (map #(.getHeader msg %) k))))

(defn http-content-type [msg]
  (if (map? msg)
    (get-in msg [:headers "content-type"])
    (.getHeader msg "Content-Type")))

(defn http-character-encoding [msg]
  (when-let [content-type (if (map? msg)
                            (get-in msg [:headers "content-type"])
                            (.getHeader ^HttpMessage msg "Content-Type"))]
    (->> (str/split content-type #"[;=]")
      (map str/trim)
      (drop-while #(not= % "charset"))
      second)))

(defn http-content-length [^HttpMessage msg]
  (when-let [content-length (.getHeader msg "Content-Length")]
    (try
      (Integer/parseInt content-length)
      (catch Exception e
        (log/error e (str "Error parsing content-length: " content-length))
        nil))))

(defn request-uri [^HttpRequest request]
  (first (str/split (.getUri request) #"[?]")))

(defn request-query-string [^HttpRequest request]
  (second (str/split (.getUri request) #"[?]")))

(defn channel-remote-host-address [^Channel channel]
  (when-let [socket-address (.getRemoteAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-host-address [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (when-let [inet-address (.getAddress ^InetSocketAddress socket-address)]
      (.getHostAddress ^InetAddress inet-address))))

(defn channel-local-port [^Channel channel]
  (when-let [socket-address (.getLocalAddress channel)]
    (.getPort ^InetSocketAddress socket-address)))

;;;

(defn to-str [x]
  (if (keyword? x)
    (name x)
    (str x)))

(defn lower-case [s]
  (when s (str/lower-case s)))

(defn string->hash [s outer-separator inner-separator]
  (when s
    (->> (str/split s outer-separator)
      (map
	#(let [pair (str/split % inner-separator)]
	   (list (first pair) (or (second pair) ""))))
      (apply concat)
      (apply hash-map))))

(defn cookie->hash [cookie]
  (string->hash cookie #"[;]" #"[=]"))

(defn hash->cookie [cookie]
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

(defn wrap-request-cookie [request]
  (if-let [cookie (:cookies request)]
    (assoc-in request [:headers "cookie"] cookie)
    request))

(defn wrap-response-cookie [response]
  (if-let [cookie (:cookies response)]
    (assoc-in response [:headers "set-cookie"] cookie)
    response))

(defn query-params
  "Returns the parsed query parameters of the request."
  ([request]
     (query-params request nil))
  ([request options]
     (when (:query-string request)
       (->> (-> request :query-string (str/split #"[&;=]"))
	 (map #(url-decode % (or (:character-encoding request) "utf-8") options))
	 (partition 2)
	 (map #(apply hash-map %))
	 (apply merge)))))

(defn split-body-params [body character-encoding options]
  (->> (-> body (bytes->string "utf-8") (str/split #"[&=]"))
    (map #(url-decode % (or character-encoding "utf-8") options))
    (partition 2)
    (map #(apply hash-map %))
    (apply merge)))

(defn body-params
  "Returns a result-channel which will emit any parameters in the body of the request."
  ([request]
     (body-params request nil))
  ([request options]
     (let [body (:body request)
	   content-type ^String (:content-type request)]
       (if-not (and content-type (.startsWith content-type "application/x-www-form-urlencoded"))
	 (run-pipeline nil)
	 (run-pipeline (if (channel? body)
			 (reduce* conj [] body)
			 body)
	   #(split-body-params % (:character-encoding request) options))))))

(defn wrap-keep-alive [request]
  (update-in request [:headers "connection"]
    #(or %
       (if (false? (:keep-alive? request))
	 "close"
	 "keep-alive"))))

(defn wrap-content-info
  [request]
  (let [headers (:headers request)]
    (if-let [content-type (or (get headers "content-type") (get headers "Content-Type"))]
      (merge 
	{:content-type content-type
	 :character-encoding (->> (str/split content-type #"[;=]") (map str/trim) (drop-while #(not= % "charset")) second)}
	request)
      request)))

(defn wrap-request [request]
  (-> request
    lowercase-headers
    wrap-request-cookie
    wrap-keep-alive
    wrap-content-info))

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

 ;;;

(defn parse-user-agent [s]
  (when s
    (let [user-agent (UserAgent/parseUserAgentString s)]
      {:browser {:name (-> user-agent .getBrowser .getName)
		 :rendering-engine (-> user-agent .getBrowser .getRenderingEngine .name)}
       :os {:name (-> user-agent .getOperatingSystem .getName)}
       :device {:mobile? (-> user-agent .getOperatingSystem .isMobileDevice)
		:type (-> user-agent .getOperatingSystem .getDeviceType .getName)}})))
