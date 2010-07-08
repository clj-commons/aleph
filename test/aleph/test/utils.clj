;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.utils
  (:use [aleph] :reload-all)
  (:use [clojure.test])
  (:import [java.util.concurrent CountDownLatch]))

(defn run-test [handler]
  (let [latch (CountDownLatch. 1)
	server (run-aleph
		 (fn [request]
		   (handler request)
		   (.countDown latch))
		 {:port 8080})]
    (.await latch)
    (Thread/sleep 1000)
    (is true)
    (close server)))
