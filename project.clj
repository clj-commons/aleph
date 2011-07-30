(defproject aleph "0.2.0-alpha3-SNAPSHOT"
  :description "a framework for asynchronous communication"
  :repositories {"jboss" "http://repository.jboss.org/nexus/content/groups/public/"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.2.0"] 
                 [org.jboss.netty/netty "3.2.4.Final"]
                 [clj-http "0.1.3"]
                 [lamina "0.4.0-alpha3-SNAPSHOT"]
                 [gloss "0.2.0-alpha2-SNAPSHOT"]
                 [user-agent-utils "1.2.3"]
                 [potemkin "0.1.0"]]
  :jvm-opts ["-server"])
