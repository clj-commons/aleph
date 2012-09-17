(defproject aleph "0.3.0-beta4"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [io.netty/netty "3.5.7.Final"]
                 [lamina "0.5.0-beta5"]
                 [gloss "0.2.2-beta2"]
                 [cheshire "4.0.1"]
                 [prxml "1.3.1"]
                 [criterium "0.3.0"]]
  :multi-deps {:all [[org.clojure/tools.logging "0.2.3"]
                     [io.netty/netty "3.5.7.Final"]
                     [lamina "0.5.0-beta5"]
                     [gloss "0.2.2-beta2"]
                     [cheshire "4.0.1"]
                     [criterium "0.3.0"]
                     [prxml "1.3.1"]]
               "master" [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]]
               "1.2" [[org.clojure/clojure "1.2.0"]]
               "1.3" [[org.clojure/clojure "1.3.0"]]}
  :dev-dependencies [
                     [codox "0.6.1"]]
  :test-selectors {:default #(not (some #{:benchmark :redis} (cons (:tag %) (keys %))))
                   :integration :redis
                   :benchmark :benchmark
                   :all (constantly true)}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC"])
