(defproject aleph.examples "0.7.1"
  :dependencies [[aleph "0.7.1"]
                 [gloss "0.2.6"]
                 [metosin/reitit "0.5.18"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.673"]
                 ;; necessary for self-signed cert example when not using OpenJDK
                 [org.bouncycastle/bcprov-jdk18on "1.75"]
                 [org.bouncycastle/bcpkix-jdk18on "1.75"]]
  :plugins [[lein-marginalia "0.9.1"]
            [lein-cljfmt "0.9.0"]])
