;; you'll need to run the script at `deps/lein-to-deps` after changing any dependencies
(def netty-version "4.1.89.Final")

(defproject aleph (or (System/getenv "PROJECT_VERSION") "0.6.1")
  :description "A framework for asynchronous communication"
  :url "https://github.com/clj-commons/aleph"
  :license {:name "MIT License"}
  :dependencies [[org.clojure/tools.logging "1.2.4" :exclusions [org.clojure/clojure]]
                 [manifold "0.3.0" :exclusions [org.clojure/tools.logging]]
                 [org.clj-commons/byte-streams "0.3.1"]
                 [org.clj-commons/dirigiste "1.0.3"]
                 [org.clj-commons/primitive-math "1.0.0"]
                 [potemkin "0.4.5"]
                 [io.netty/netty-transport ~netty-version]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-x86_64"]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-aarch_64"]
                 [io.netty/netty-transport-native-kqueue ~netty-version :classifier "osx-x86_64"]
                 [io.netty.incubator/netty-incubator-transport-native-io_uring "0.0.18.Final" :classifier "linux-x86_64"]
                 [io.netty.incubator/netty-incubator-transport-native-io_uring "0.0.18.Final" :classifier "linux-aarch_64"]
                 [io.netty/netty-codec ~netty-version]
                 [io.netty/netty-codec-http ~netty-version]
                 [io.netty/netty-codec-http2 ~netty-version]
                 [io.netty/netty-handler ~netty-version]
                 [io.netty/netty-handler-proxy ~netty-version]
                 [io.netty/netty-resolver ~netty-version]
                 [io.netty/netty-resolver-dns ~netty-version]
                 [metosin/malli "0.10.4" :exclusions [org.clojure/clojure]]]
  :profiles {:dev  {:dependencies [[org.clojure/clojure "1.11.1"]
                                   [criterium "0.4.6"]
                                   [cheshire "5.10.0"]
                                   [org.slf4j/slf4j-simple "1.7.30"]
                                   [com.cognitect/transit-clj "1.0.324"]
                                   [spootnik/signal "0.2.4"]
                                   ;; This is for self-generating certs for testing ONLY:
                                   [org.bouncycastle/bcprov-jdk18on "1.72"]
                                   [org.bouncycastle/bcpkix-jdk18on "1.72"]]
                    :jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"]}
             :test {:jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=off"]}
             :leak-level-paranoid {:jvm-opts ["-Dio.netty.leakDetectionLevel=PARANOID"]}
             :pedantic {:pedantic? :abort}}
  :java-source-paths ["src/aleph/utils"]
  :test-selectors {:default #(not
                              (some #{:benchmark :stress}
                                    (cons (:tag %) (keys %))))
                   :benchmark :benchmark
                   :stress :stress
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server"
                       "-Xmx2g"
                       "-XX:+HeapDumpOnOutOfMemoryError"
                       #_"-XX:+PrintCompilation"
                       #_"-XX:+UnlockDiagnosticVMOptions"
                       #_"-XX:+PrintInlining"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :global-vars {*warn-on-reflection* true})
