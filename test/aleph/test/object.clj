;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.object
  (:use
    [aleph object]
    [clojure test]
    [lamina core]))

(defn echo-handler [ch _]
  (siphon ch ch))

(deftest test-echo-server
  (let [stop-fn (start-object-server echo-handler {:port 8888})]
    (try
      (let [ch @(object-client {:host "localhost", :port 8888})
            messages (map vector (range 1e2))]
        (apply enqueue ch messages)
        (= messages (->> ch (take* (count messages)) channel->lazy-seq)))
      (finally
        (stop-fn)))))
