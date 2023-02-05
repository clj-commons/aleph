(ns aleph.http-codec-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [aleph.tcp :as tcp]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as s])
  (:import
   (aleph.protobuf Schema$Person)
   (io.netty.channel ChannelPipeline)
   (io.netty.handler.codec.protobuf ProtobufEncoder ProtobufDecoder)
   (java.lang AutoCloseable)))

(defn- make-person [name]
  (-> (Schema$Person/newBuilder)
      (.setName name)
      (.build)))

(defn- parse-person ^Schema$Person [^bytes ba]
  (Schema$Person/parseFrom ^bytes ba))

(defn add-protobuf-encoder [^ChannelPipeline pipeline]
  (.addLast pipeline
             "protobuf-encoder"
             (ProtobufEncoder.))
  pipeline)

(defn add-protobuf-decoder [^ChannelPipeline pipeline]
  (.addLast pipeline
             "protobuf-decoder"
             (ProtobufDecoder. (Schema$Person/getDefaultInstance)))
  pipeline)

(defn tcp-handler [s _]
  (prn "cool")
  (is (= "John" (.getName (parse-person @(s/take! s)))))
  (prn "cool2")
  @(s/put! s (make-person "Doe"))
  (prn "close!")
  (s/close! s))

(deftest protobuf
  (testing "TCP server"
    (prn "what?")
    (with-open [server ^AutoCloseable (tcp/start-server (bound-fn* tcp-handler) {:port 0
                                                                                 :coerce-fn identity
                                                                                 :pipeline-transform (comp add-protobuf-encoder add-protobuf-decoder)})]
      (prn "youpi!")
      (let [s @(tcp/client {:host "localhost"
                            :port (netty/port server)
                            :coerce-fn identity
                            :pipeline-transform (comp add-protobuf-encoder add-protobuf-decoder)})]
                                                     
        @(s/put! s (.getBytes "hello"))
        #_#_#_
        @(s/put! s (make-person "John"))
        @(s/put! s (make-person "John"))
        @(s/put! s (make-person "John"))
        (prn "done..")
        (is (= "Doe" (.getName (parse-person @(s/take! s)))))
        (Thread/sleep 500)))))
