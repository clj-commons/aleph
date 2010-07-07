(ns aleph.buffer
  (:use [clojure.contrib.def :only (defvar-)])
  (:import [org.jboss.netty.buffer ChannelBuffer DirectChannelBufferFactory]))

(defn- to-byte [b]
  (byte
    (if (instance? Boolean b)
      (if b 1 0)
      b)))

(defvar- write-fns
  {:float #(.writeFloat ^ChannelBuffer %1 %2)
   :double #(.writeDouble ^ChannelBuffer %1 %2)
   :int #(.writeInt ^ChannelBuffer %1 %2)
   :long #(.writeLong ^ChannelBuffer %1 %2)
   :short #(.writeShort ^ChannelBuffer %1 %2)
   :char #(.writeChar ^ChannelBuffer %1 %2)
   :byte #(.writeByte ^ChannelBuffer %1 (to-byte %2))})

(defvar- read-fns
  {:float #(.readFloat ^ChannelBuffer %)
   :double #(.readDouble ^ChannelBuffer %)
   :int #(.readInt ^ChannelBuffer %)
   :long #(.readLong ^ChannelBuffer %)
   :short #(.readShort ^ChannelBuffer %)
   :char #(.readChar ^ChannelBuffer %)
   :byte #(.readByte ^ChannelBuffer %)})

(defvar- type-size
  {:float 4
   :double 8
   :int 4
   :long 8
   :short 2
   :char 2
   :byte 1})

(defn- wrap-sig [sig]
  (if (sequential? sig) sig [sig]))

(defn- num-bytes [sig]
  (reduce + (map type-size sig)))

(defn create-buffer [dim]
  (let [buf (.getBuffer (DirectChannelBufferFactory/getInstance) dim)]
    (.writerIndex buf dim)
    buf))

(defn- shift-read [^ChannelBuffer buf offset]
  (let [buf (.duplicate buf)]
    (.readerIndex buf (+ offset (.readerIndex buf)))
    buf))

(defn as-types [buf sig]
  (let [sig (wrap-sig sig)
	num-bytes (num-bytes sig)]
    (when (>= (.readableBytes buf) num-bytes)
      (lazy-seq
	(cons
	  (let [buf (.duplicate buf)
		res (map #((read-fns %1) %2) sig (repeat buf))]
	    (if (= 1 (count sig))
	      (first res)
	      res))
	  (as-types (shift-read buf num-bytes) sig))))))
