;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.handlers
  (:use [aleph] :reload-all)
  (:use
    [aleph.test.utils]
    [clojure.test]
    [clojure.contrib.duck-streams :only [pwd]])
  (:import [java.io File]))

(defn hello-world-handler [request]
  (respond! request
    {:status 200
     :header {"Content-Type" "text/html"}
     :body "Hello World\n"}))

'(deftest hello-world
  (println "Testing Hello World")
  (run-test hello-world-handler))

(defn file-handler [request]
  (respond! request
    {:status 200
     :body (File. (str (pwd) "/test/starry_night.jpg"))}))

(deftest file 
  (println "Testing File")
  (run-test file-handler))
