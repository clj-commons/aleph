(ns aleph.lein-to-deps
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]))

(defn convert-lein-deps [deps]
  (into {}
        (map (fn [[name version & {:keys [classifier exclusions]}]]
               (let [name (if classifier
                            (symbol (str name "$" classifier))
                            name)
                     params (cond-> {:mvn/version version}
                              (seq exclusions)
                              (assoc :exclusions (mapv first exclusions)))]
                 [name params])))
        deps))

(defn relativize-path [p]
  (-> (System/getProperty "user.dir")
      io/file
      .toURI
      (.relativize (-> p io/file .toURI))
      .getPath))

(defn write-edn-file [path data]
  (binding [*print-namespace-maps* false]
    (with-open [w (io/writer (io/file path))]
      (binding [*out* w]
        (println ";; DO NOT EDIT MANUALLY - generated from project.clj via deps/lein-to-deps")
        (pp/pprint data)))))

(defn run []
  (let [pprinted-project-clj (read-string (slurp *in*))
        deps (convert-lein-deps (:dependencies pprinted-project-clj))
        class-dir (relativize-path (:compile-path pprinted-project-clj))
        source-paths (conj (mapv relativize-path (:source-paths pprinted-project-clj))
                           class-dir)
        java-source-dirs (mapv relativize-path (:java-source-paths pprinted-project-clj))]
    (write-edn-file "deps.edn"
                    {:paths source-paths
                     :deps deps
                     :aliases
                     {:build
                      {:paths ["deps"]
                       :deps
                       {'io.github.clojure/tools.build
                        {:git/sha "cde5adf5d56fe7238de509339e63627f438e5d4b"
                         :git/url "https://github.com/clojure/tools.build.git"}},
                       :ns-default 'aleph.build}}
                     :deps/prep-lib
                     {:ensure class-dir
                      :alias :build
                      :fn 'compile-java}})
    (write-edn-file "deps/aleph/build.edn"
                    {:javac {:src-dirs java-source-dirs
                             :class-dir class-dir
                             :javac-opts (:javac-options pprinted-project-clj)}})))
