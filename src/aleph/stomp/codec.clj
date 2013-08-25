;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.stomp.codec
  (:use
    [gloss core])
  (:require
    [gloss.io :as io]
    [clojure.string :as str]))

;;;

(defn content-type [headers]
  (get headers "content-type"))

(defn content-length [headers]
  (when (contains? headers "content-length")
    (try
      (Integer/parseInt (get headers "content-length"))
      (catch Exception e
        nil))))

(defn character-encoding [headers]
  (when-let [content-type (get headers "content-type")]
    (->> (str/split content-type #"[;=]")
      (map str/trim)
      (drop-while #(not= % "charset"))
      second)))

;;;

(def heartbeat-codec
  (compile-frame
    nil-frame
    identity
    (fn [_] {:command :heartbeat})))

(def command-codec
  (compile-frame (string :utf-8 :delimiters ["\n"])
    #(if (nil? %)
       ""
       (-> % name str/upper-case))
    #(if (= :heartbeat %)
       ""
       (when-not (and (string? %) (empty? %))
         (-> % str/lower-case keyword)))))

(def utf-8-codec
  (string :utf-8))

(defn decode-string [s]
  (-> s
    (str/replace #"\\n" "\n")
    (str/replace #"\\c" ":")
    (str/replace #"\\\\" "\\")))

(defn encode-string [s]
  (-> s
    (str/replace #"\\" "\\\\")
    (str/replace #"\n" "\\\\n")
    (str/replace #":" "\\\\c")))

(def headers-codec
  (compile-frame (delimited-block ["\n\n"] false)
    (fn [m]
      (->> m
        (map (fn [[k v]] (str (encode-string (name k)) ":" (encode-string v))))
        (interpose "\n")
        (apply str)
        (io/encode utf-8-codec)))
    (fn [b]
      (let [s ^String (io/decode utf-8-codec b)
            s (.substring s 0 (- (count s) 2))]
        (when-not (empty? s)
          (->> (str/split s #"[:\n]")
            (map decode-string)
            (apply hash-map)))))))

(defn command->headers-codec [command]
  (compile-frame
    headers-codec
    #(map
       (fn [[k v]]
         [(name k) (str v)])
       (:headers %))
    #(hash-map
       :command command
       :headers (->> % reverse (into {})))))

(defn command->headers-and-body-codec [command]
  (header (command->headers-codec command)
    (fn [{:keys [headers] :as message}]
      (let [encoding (or (character-encoding headers) "utf-8")]
        (compile-frame
          (if-let [length (content-length headers)]
            (string encoding :length length :suffix "\0")
            (string encoding :delimiters ["\0"]))
          #(or (:body %) "")
          #(assoc message :body %))))
    identity))

(def message-codec
  (header command-codec
    (fn [command]
      (if (or
            (= command :heartbeat)
            (and (string? command) (empty? command)))
        heartbeat-codec
        (command->headers-and-body-codec command)))
    :command))
