(ns aleph.http-codec-test
  (:require [aleph.http :as http]
            [aleph.tcp :as tcp]
            [clojure.test :as t]
            [manifold.stream :as s])
  (:import
   (aleph.protobuf Schema$Person)
   (io.netty.channel ChannelPipeline)
   (io.netty.handler.codec LengthFieldPrepender)
   (io.netty.handler.codec.protobuf ProtobufEncoder)))

(defn- make-person [name]
  (-> (Schema$Person/newBuilder)
      (.setName name)
      (.build)))

(defn add-protobuf-encoder [^ChannelPipeline pipeline]
  (.addLast pipeline
             "protobuf-decoder"
             (ProtobufEncoder.)))

(defn handler [s _]
  (s/put! s (make-person "Doe"))
  (s/close! s))

(def server (tcp/start-server #'handler {:port 9999
                                         :pipeline-transform add-protobuf-encoder}))

(Schema$Person/parseFrom)

(defn call []
  (let [s @(tcp/client {:host "localhost"
                        :port 9999})]
    (prn (Schema$Person/parseFrom @(s/take! s)))))

(call)

(.close server)
