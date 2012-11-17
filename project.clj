(defproject aleph "0.3.0-SNAPSHOT"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/data.xml "0.0.6"]
                 [io.netty/netty "3.5.8.Final"]
                 [lamina "0.5.0-beta7"]
                 [gloss "0.2.2-beta3"]
                 [cheshire "4.0.1"]
                 [criterium "0.3.0"]
                 [commons-codec/commons-codec "1.7"]
                 [org.apache.commons/commons-compress "1.4.1"]]
  :multi-deps {:all [[org.clojure/tools.logging "0.2.3"]
                     [org.clojure/data.xml "0.0.6"]
                     [io.netty/netty "3.5.8.Final"]
                     [lamina "0.5.0-beta7"]
                     [gloss "0.2.2-beta3"]
                     [cheshire "4.0.1"]
                     [criterium "0.3.0"]
                     [commons-codec/commons-codec "1.7"]
                     [org.apache.commons/commons-compress "1.4.1"]]
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
