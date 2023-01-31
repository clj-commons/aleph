(ns aleph.http-codec-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [aleph.tcp :as tcp]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as s])
  (:import
   (aleph.protobuf Schema$Person)
   (io.netty.channel ChannelPipeline)
   (io.netty.handler.codec.protobuf ProtobufEncoder)
   (java.lang AutoCloseable)))

(defn- make-person [name]
  (-> (Schema$Person/newBuilder)
      (.setName name)
      (.build)))

(defn add-protobuf-encoder [^ChannelPipeline pipeline]
  (.addLast pipeline
             "protobuf-decoder"
             (ProtobufEncoder.)))

(defn tcp-handler [s _]
  (s/put! s (make-person "Doe"))
  (s/close! s))

(deftest protobuf
  (testing "TCP server"
    (with-open [server ^AutoCloseable (tcp/start-server #'tcp-handler {:port 0
                                                                       :coerce-fn identity
                                                                       :pipeline-transform add-protobuf-encoder})]
      (let [s @(tcp/client {:host "localhost"
                            :port (netty/port server)})]
        (is (= "Doe" (.getName (Schema$Person/parseFrom ^bytes @(s/take! s)))))))))
