(defproject aleph.examples "0.9.3"
  :dependencies [[aleph "0.9.3"]
                 [gloss "0.2.6"]
                 [metosin/reitit "0.9.1"]
                 [org.clojure/clojure "1.12.1"]
                 [org.clojure/core.async "1.8.741"]
                 ;; necessary for self-signed cert example when not using OpenJDK
                 [org.bouncycastle/bcprov-jdk18on "1.81"]
                 [org.bouncycastle/bcpkix-jdk18on "1.81"]]
  :plugins [[lein-marginalia "0.9.2"]
            [lein-cljfmt "0.9.2"]])
