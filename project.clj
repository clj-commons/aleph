(defproject aleph "0.3.0-SNAPSHOT"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/tools.logging "0.2.3"]
                 [org.clojure/data.xml "0.0.7"]
                 [io.netty/netty "3.6.2.Final"]
                 [lamina "0.5.0-beta13"]
                 [gloss "0.2.2-beta5"]
                 [cheshire "5.0.1"]
                 [commons-codec/commons-codec "1.7"]
                 [org.apache.commons/commons-compress "1.4.1"]]
  :exclusions [org.clojure/contrib
               org.clojure/clojure-contrib]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.4.0"]
                                  [criterium "0.3.1"]]}
             :1.2 {:dependencies [[org.clojure/clojure "1.2.1"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]}}
  :aliases {"all" ["with-profile" "1.2,dev:1.3,dev:dev:1.5,dev"]}
  :plugins [[codox "0.6.2"]]
  :test-selectors {:default #(not (some #{:benchmark :redis} (cons (:tag %) (keys %))))
                   :integration :redis
                   :benchmark :benchmark
                   :all (constantly true)}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC" "-Xmx4g"]
  :warn-on-reflection true)
