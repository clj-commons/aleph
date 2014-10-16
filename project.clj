(defproject aleph "0.4.0-SNAPSHOT"
  :description "a library for asynchronous network communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "MIT License"}
  :dependencies [[org.clojure/tools.logging "0.3.0"]
                 [io.netty/netty-all "4.0.23.Final"]
                 [io.aleph/dirigiste "0.1.0-alpha2"]
                 [manifold "0.1.0-beta1"]
                 [byte-streams "0.2.0-alpha1"]
                 [potemkin "0.3.8"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                  [criterium "0.4.3"]]}}
  :plugins [[codox "0.6.2"]]
  :test-selectors {:default (complement :benchmark)
                   :benchmark :benchmark
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-XX:+UseConcMarkSweepGC" "-Xmx4g"]
  :global-vars {*warn-on-reflection* true})
