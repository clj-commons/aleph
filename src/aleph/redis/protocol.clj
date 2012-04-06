;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  aleph.redis.protocol
  (:use
    [lamina core]
    [gloss core]))

(defn string-prefix [count-offset]
  (prefix (string-integer :ascii :delimiters ["\r\n"] :as-str true)
    #(if (neg? %) 0 (+ % count-offset))
    #(if-not % -1 (- % count-offset))))

(def format-byte
  (enum :byte
    {:error \-
     :single-line \+
     :integer \:
     :bulk \$
     :multi-bulk \*}))

(defn codec-map [charset]
  (let [m {:error (string charset :delimiters ["\r\n"])
	   :single-line (string charset :delimiters ["\r\n"])
	   :integer (string-integer :ascii :delimiters ["\r\n"])
	   :bulk (finite-frame (string-prefix 2) (string charset :suffix "\r\n"))}
	m (into {}
	    (map
	      (fn [[k v]] [k (compile-frame [k v])])
	      m))
	m (atom m)]
    (swap! m assoc
      :multi-bulk (compile-frame
		    [:multi-bulk
		     (repeated
		       (header format-byte #(@m %) first)
		       :prefix (string-prefix 0))]))
    @m))

(defn process-response [rsp]
  (case (first rsp)
    :error (Exception. (str (second rsp)))
    :multi-bulk (map process-response (second rsp))
    (second rsp)))

(defn process-request [req]
  [:multi-bulk (map #(vector :bulk (if (keyword? %) (name %) (str %))) req)])

(defn redis-codec [charset]
  (let [codecs (codec-map charset)]
    (compile-frame (header format-byte codecs first)
      process-request
      process-response)))
