(ns aleph.http-codec-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [aleph.tcp :as tcp]
            [aleph.udp :as udp]
            [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as d]
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
  (d/chain (s/take! s)
           (fn [data]
             (s/put! s (parse-person data)))))

(deftest protobuf
  (testing "TCP server"
    (with-open [server ^AutoCloseable (tcp/start-server tcp-handler
                                                        {:port 0
                                                         :coerce-fn identity
                                                         :pipeline-transform (comp add-protobuf-encoder add-protobuf-decoder)})]
      (let [s @(tcp/client {:host "localhost"
                            :port (netty/port server)
                            :coerce-fn identity
                            :pipeline-transform (comp add-protobuf-encoder add-protobuf-decoder)})]

        @(s/put! s (make-person "John Doe"))
        (is (= "John Doe" (.getName (parse-person @(s/take! s))))))))

  (testing "UDP server"
    (let [s @(udp/socket {:port 10001 :raw-stream? true :transport :epoll :coerce-fn identity})]
      (try
        (s/put! s {:host "localhost", :port 10001, :message (make-person "John Doe")})
        (is (= "foo"
               (parse-person @(s/try-take! s 200))))
        (finally
          (s/close! s))))))
