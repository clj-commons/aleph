;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.utils)

(defn cookies->hash [cookies]
  (when cookies
    (lazy-seq
      (apply hash-map
	(seq (.split cookies "[=;]"))))))

(defn hash->cookies [cookies]
  (when cookies
    (if (map? cookies)
      (->> cookies
	(map #(str (first %) "=" (second %)))
	(interpose ";")
	(apply str))
      cookies)))
