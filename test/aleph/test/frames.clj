;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.frames
  (:use
    [aleph.tcp]
    [lamina.core]
    [gloss.core]
    [clojure.test]))

(defn test-roundtrip
  [frame values]
  (let [server (start-tcp-server (fn [ch _] (siphon ch ch)) {:port 10000 :frame frame})]
    (let [client (wait-for-pipeline (tcp-client {:host "localhost" :port 10000 :frame frame}))]
      (try
	(apply enqueue client values)
	(is (= values (take (count values) (lazy-channel-seq client))))
	(finally
	  (enqueue-and-close client nil)
	  (server))))))

(deftest test-frames
  (test-roundtrip
    :int32
    (range 10))
  (test-roundtrip
    [:int32 :int32]
    (partition 2 (range 10)))
  (test-roundtrip
    (enum :int16 :a :b)
    [:a :b]))
