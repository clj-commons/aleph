;; you'll need to run the script at `deps/lein-to-deps` after changing any dependencies
(def netty-version "4.1.130.Final")
(def brotli-version "1.20.0")


(defproject aleph (or (System/getenv "PROJECT_VERSION") "0.9.4")
  :description "A framework for asynchronous communication"
  :url "https://github.com/clj-commons/aleph"
  :license {:name "MIT License"}
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/tools.logging "1.3.1" :exclusions [org.clojure/clojure]]
                 [manifold "0.5.0" :exclusions [org.clojure/tools.logging]]
                 [org.clj-commons/byte-streams "0.3.4"]
                 [org.clj-commons/dirigiste "1.0.4"]
                 [org.clj-commons/primitive-math "1.0.1"]
                 [potemkin "0.4.8"]
                 [io.netty/netty-transport ~netty-version]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-x86_64"]
                 [io.netty/netty-transport-native-epoll ~netty-version :classifier "linux-aarch_64"]
                 [io.netty/netty-transport-native-kqueue ~netty-version :classifier "osx-x86_64"]
                 [io.netty.incubator/netty-incubator-transport-native-io_uring "0.0.26.Final" :classifier "linux-x86_64"]
                 [io.netty.incubator/netty-incubator-transport-native-io_uring "0.0.26.Final" :classifier "linux-aarch_64"]
                 [io.netty/netty-codec ~netty-version]
                 [io.netty/netty-codec-http ~netty-version]
                 [io.netty/netty-codec-http2 ~netty-version]
                 [io.netty/netty-handler ~netty-version]
                 [io.netty/netty-handler-proxy ~netty-version]
                 [io.netty/netty-resolver ~netty-version]
                 [io.netty/netty-resolver-dns ~netty-version]
                 [metosin/malli "0.20.0" :exclusions [org.clojure/clojure]]]
  :profiles {:dev                 {:dependencies [[criterium "0.4.6"]
                                                  [cheshire "6.1.0"]
                                                  [org.slf4j/slf4j-simple "2.0.17"]
                                                  [com.cognitect/transit-clj "1.0.333"]
                                                  [spootnik/signal "0.2.5"]
                                                  ;; This is for dev and testing ONLY, not recommended for prod
                                                  [org.bouncycastle/bcprov-jdk18on "1.83"]
                                                  [org.bouncycastle/bcpkix-jdk18on "1.83" :exclusions [org.bouncycastle/bcutil-jdk18on]]
                                                  ;;[org.bouncycastle/bctls-jdk18on "1.75"]
                                                  [io.netty/netty-tcnative-boringssl-static "2.0.74.Final"]
                                                  ;;[com.aayushatharva.brotli4j/all ~brotli-version]
                                                  [com.aayushatharva.brotli4j/brotli4j ~brotli-version]
                                                  [com.aayushatharva.brotli4j/service ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-linux-aarch64 ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-linux-armv7 ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-linux-x86_64 ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-osx-aarch64 ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-osx-x86_64 ~brotli-version]
                                                  [com.aayushatharva.brotli4j/native-windows-x86_64 ~brotli-version]
                                                  [com.github.luben/zstd-jni "1.5.7-6"]]
                                   :jvm-opts     ["-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
                                                  "-Dorg.slf4j.simpleLogger.showThreadName=false"
                                                  "-Dorg.slf4j.simpleLogger.showThreadId=true"
                                                  "-Dorg.slf4j.simpleLogger.showLogName=false"
                                                  "-Dorg.slf4j.simpleLogger.showShortLogName=true"
                                                  "-Dorg.slf4j.simpleLogger.showDateTime=true"
                                                  "-Dorg.slf4j.simpleLogger.log.io.netty.util=error"
                                                  "-Dorg.slf4j.simpleLogger.log.io.netty.channel=warn"]}
             :test                {:jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=off"]}
             :leak-detection {:aot [aleph.resource-leak-detector]
                              :jvm-opts ["-Dio.netty.leakDetection.level=PARANOID"
                                         "-Dio.netty.customResourceLeakDetector=aleph.resource_leak_detector"
                                         "-Dorg.slf4j.simpleLogger.log.aleph.resource-leak-detector=info"
                                         ;; These settings empirically make leak detection more reliable
                                         "-Dio.netty.leakDetection.targetRecords=1"
                                         "-Dio.netty.allocator.type=unpooled"]}
             :dropped-error-deferred-detection {:jvm-opts ["-Dorg.slf4j.simpleLogger.log.manifold.debug=warn"
                                                           "-Daleph.testutils.detect-dropped-error-deferreds=true"]}
             :pedantic            {:pedantic? :abort}
             :trace               {:jvm-opts ["-Dorg.slf4j.simpleLogger.defaultLogLevel=trace"]}
             :profile             {:dependencies [[com.clojure-goes-fast/clj-async-profiler "1.7.0"]]
                                   :jvm-opts     ["-Djdk.attach.allowAttachSelf"]}}
  :java-source-paths ["src-java"]
  :test-selectors {:default   #(not
                                 (some #{:benchmark :stress :leak}
                                   (cons (:tag %) (keys %))))
                   :benchmark :benchmark
                   :stress    :stress
                   :leak      :leak ; requires :leak-detection profile
                   :default+leak #(not
                                   (some #{:benchmark :stress}
                                         (cons (:tag %) (keys %))))
                   :all       (constantly true)}
  :jvm-opts ^:replace ["-server"
                       "-Xmx2g"
                       "-XX:+HeapDumpOnOutOfMemoryError"
                       "-XX:+PrintCommandLineFlags"
                       #_"-XX:+PrintCompilation"
                       #_"-XX:+UnlockDiagnosticVMOptions"
                       #_"-XX:+PrintInlining"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :global-vars {*warn-on-reflection* true})
