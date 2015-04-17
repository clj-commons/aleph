(defproject aleph "0.4.0"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "MIT License"}
  :dependencies [[org.clojure/tools.logging "0.3.1"]
                 [io.netty/netty-all "4.1.0.Beta4"]
                 [io.aleph/dirigiste "0.1.0"]
                 [manifold "0.1.0"]
                 [byte-streams "0.2.0"]
                 [potemkin "0.3.13"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                                  [criterium "0.4.3"]
                                  [org.clojure/tools.trace "0.7.8"]
                                  #_[codox-md "0.2.0" :exclusions [org.clojure/clojure]]]}}
  :codox {:src-dir-uri "https://github.com/ztellman/aleph/tree/0.4.0/"
          :src-linenum-anchor-prefix "L"
          :defaults {:doc/format :markdown}
          :include [aleph.tcp
                    aleph.udp
                    aleph.http
                    aleph.flow]
          :output-dir "doc"}
  :plugins [[codox "0.8.10"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server"
                       "-XX:+UseConcMarkSweepGC"
                       "-Xmx4g"
                       #_"-XX:+PrintCompilation"
                       #_"-XX:+UnlockDiagnosticVMOptions"
                       #_"-XX:+PrintInlining"]
  :global-vars {*warn-on-reflection* true})
