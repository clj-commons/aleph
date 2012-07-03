(defproject aleph "0.2.1-rc5"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"
                 "sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/data.json "0.1.1"]
                 [prxml "1.3.1"]
                 [io.netty/netty "3.4.2.Final"]
                 [clj-http "0.1.3"]
                 [lamina "0.4.1-rc3"]
                 [gloss "0.2.1"]
                 [user-agent-utils "1.2.3"]
                 [potemkin "0.1.3"]]
  :multi-deps {:all [[org.clojure/tools.logging "0.2.3"]
                     [org.clojure/data.json "0.1.1"]
                     [prxml "1.3.1"]	
                     [io.netty/netty "3.4.2.Final"]
                     [clj-http "0.1.3"]
                     [lamina "0.4.1"]
                     [gloss "0.2.1"]
                     [user-agent-utils "1.2.3"]
                     [potemkin "0.1.3"]]
               "1.2" [[org.clojure/clojure "1.2.1"]]
               "1.4" [[org.clojure/clojure "1.4.0"]]}
  :jvm-opts ["-server" "-XX:+UseConcMarkSweepGC"])
