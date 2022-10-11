;; you'll need to run the script at `deps/lein-to-deps` after changing any dependencies
(def netty-version "4.1.82.Final")

(defproject aleph (or (System/getenv "PROJECT_VERSION") "0.5.0")
  :description "A framework for asynchronous communication"
  :repositories {"jboss"               "https://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :url "https://github.com/clj-commons/aleph"
  :license {:name "MIT License"}
  :dependencies [[org.clojure/tools.logging "1.1.0" :exclusions [org.clojure/clojure]]
                 [org.clj-commons/dirigiste "1.0.1"]
                 [manifold "0.2.4"]
                 [org.clj-commons/byte-streams "0.3.1"]
                 [org.clj-commons/primitive-math "1.0.0"]
                 [potemkin "0.4.5"]
                 [io.netty/netty-transport ~netty-version]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-x86_64"]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-aarch_64"]
                 [io.netty/netty-codec ~netty-version]
                 [io.netty/netty-codec-http ~netty-version]
                 [io.netty/netty-handler ~netty-version]
                 [io.netty/netty-handler-proxy ~netty-version]
                 [io.netty/netty-resolver ~netty-version]
                 [io.netty/netty-resolver-dns ~netty-version]]
  :profiles {:dev          {:dependencies [[org.clojure/clojure "1.11.1"]
                                           [criterium "0.4.6"]
                                           [cheshire "5.10.0"]
                                           [org.slf4j/slf4j-simple "1.7.30"]
                                           [com.cognitect/transit-clj "1.0.324"]
                                           [spootnik/signal "0.2.4"]
                                           [me.mourjo/dynamic-redef "0.1.0"]

                                           ;; for testing clj-http parity
                                           [clj-http "3.12.3"]
                                           [ring/ring-jetty-adapter "1.9.3"]
                                           [org.apache.logging.log4j/log4j-api "2.17.1"]
                                           [org.apache.logging.log4j/log4j-core "2.17.1"]
                                           [org.apache.logging.log4j/log4j-1.2-api "2.17.1"]]}
             :lein-to-deps {:source-paths ["deps"]}
             ;; This is for self-generating certs for testing ONLY:
             :test         {:dependencies  [[org.bouncycastle/bcprov-jdk15on "1.69"]
                                            [org.bouncycastle/bcpkix-jdk15on "1.69"]]
                            :javac-options ^:replace ["--release" "12"] ; necessary for some tests
                            :jvm-opts      ["-Dorg.slf4j.simpleLogger.defaultLogLevel=off"]}}
  :codox {:src-dir-uri               "https://github.com/ztellman/aleph/tree/master/"
          :src-linenum-anchor-prefix "L"
          :defaults                  {:doc/format :markdown}
          :include                   [aleph.tcp
                                      aleph.udp
                                      aleph.http
                                      aleph.flow]
          :output-dir                "doc"}
  :plugins [[lein-codox "0.10.7"]
            [lein-marginalia "0.9.1"]
            [lein-pprint "1.3.2"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :java-source-paths ["src/aleph/utils"]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :test-selectors {:default     #(not
                                   (some #{:benchmark :stress :integration :ignore}
                                     (cons (:tag %) (keys %))))
                   :benchmark   :benchmark
                   :integration :integration
                   :stress      :stress
                   :clj-http    [(fn clj-http-ns-pred [namespc & _]
                                   (.contains (str namespc) "clj-http"))
                                 (fn clj-http-test-pred [m & _]
                                   (not (:ignore m)))]
                   :all         (constantly true)}
  :jvm-opts ^:replace ["-server"
                       "-Xmx2g"
                       "-XX:+HeapDumpOnOutOfMemoryError"
                       #_"-XX:+PrintCompilation"
                       #_"-XX:+UnlockDiagnosticVMOptions"
                       #_"-XX:+PrintInlining"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :global-vars {*warn-on-reflection* true})
