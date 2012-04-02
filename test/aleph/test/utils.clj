;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.utils)

(if (and (= 1 (:major *clojure-version*)) (= 2 (:minor *clojure-version*)))
  (defmacro bench [& _])
  (do
    (require '[criterium.core])
    (defmacro bench [name & body]
      `(do
         (println "\n-----\n" ~name "\n-----\n")
         (criterium.core/bench
           (do ~@body)
           :reduce-with #(and %1 %2))
         (flush)))
    (defmacro quick-bench [name & body]
      `(do
         (println "\n-----\n" ~name "\n-----\n")
         (criterium.core/quick-bench
           (do ~@body)
           :reduce-with #(and %1 %2))
         (flush)))))

