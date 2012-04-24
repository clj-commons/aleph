(defproject aleph "0.3.0-SNAPSHOT"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [io.netty/netty "3.3.1.Final"]
                 [lamina "0.5.0-SNAPSHOT"]
                 [gloss "0.2.2-SNAPSHOT"]
                 [clj-json "0.5.0"]
                 [prxml "1.3.1"]]
  :dev-dependencies [[criterium "0.2.1-SNAPSHOT"]
                     [codox "0.4.1"]]
  :test-selectors {:default #(not (some #{:benchmark} (cons (:tag %) (keys %))))
                   :benchmark :benchmark
                   :all (constantly true)}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC"])
