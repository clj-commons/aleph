(ns aleph.build
  (:require [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn"}))
(def build-edn (read-string (slurp "deps/aleph/build.edn")))

(defn compile-java [_]
  (b/javac (assoc (:javac build-edn) :basis basis)))
