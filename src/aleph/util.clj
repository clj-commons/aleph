(ns aleph.util
  (:require [manifold.deferred :as d]))

(defn on-error
  [d f]
  (d/on-realized d identity f))

(defn propagate-error
  "Registers an error callback with d which will attempt to propagate the error to destination.

  If the error was propagated (i.e. destination wasn't yet realized), on-propagate is invoked with
  the error value.

  Returns d."
  ([d destination]
   (propagate-error d destination identity))
  ([d destination on-propagate]
   (on-error d (fn [e]
                 (when (d/error! destination e)
                   (on-propagate e))))))
