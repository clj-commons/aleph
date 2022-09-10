(ns aleph.build
  (:require [clojure.tools.build.api :as b]))

;; WARNING: This is *not* Aleph's official build process. It merely
;; replicates a subset of the official Leiningen-based build process
;; to allow Aleph to be used as a git or local root dependency.

(def basis (b/create-basis {:project "deps.edn"}))
(def build-edn (read-string (slurp "deps/aleph/build.edn")))

(defn compile-java [_]
  (b/javac (assoc (:javac build-edn) :basis basis)))
