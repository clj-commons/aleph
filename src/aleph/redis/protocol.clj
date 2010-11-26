;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.redis.protocol
  (:use
    [lamina core]
    [gloss core]))

(defn string-prefix [count-offset]
  (prefix (string-integer :ascii :delimiters ["\r\n"])
    #(if (neg? %) 0 (- % count-offset))
    #(if-not % -1 (+ % count-offset))))

(def leading-byte
  (enum :byte
    {:error \-
     :single-line \+
     :integer \:
     :bulk \$
     :multi-bulk \*}))

(defn codec-map [charset]
  (let [bulk (compile-frame
	       [:bulk
		(finite-frame
		  (string-prefix -2)
		  (string charset :delimiters ["\r\n"]))])
	bulk* (header leading-byte (constantly bulk) first)
	m {:error [:error (string charset :delimiters ["\r\n"])]
	   :single-line [:single-line (string charset :delimiters ["\r\n"])]
	   :integer [:integer (string-integer :ascii :delimiters ["\r\n"])]
	   :bulk bulk
	   :multi-bulk [:multi-bulk (repeated bulk* :prefix (string-prefix 0))]}]
    (zipmap (keys m) (map compile-frame (vals m)))))

(defn redis-codec [charset]
  (let [codecs (codec-map charset)]
    (header leading-byte codecs first)))

(defn process-response [rsp]
  (if (= :error (first rsp))
    (str "ERROR: " (second rsp)) ;;(enqueue (:error ch) (second rsp))
    (condp = (first rsp)
      :single-line (second rsp)
      :integer (second rsp)
      :bulk (second rsp)
      :multi-bulk (map second (second rsp)))))

(defn process-request [req]
  [:multi-bulk (map #(list :bulk %) req)])





