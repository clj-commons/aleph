;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns 
  aleph.formats
  (:require
    [clojure.contrib.json :as json]
    [clojure.xml :as xml]
    [clojure.contrib.prxml :as prxml])
  (:import
    [java.io
     InputStream
     InputStreamReader
     PrintWriter
     ByteArrayOutputStream]
    [java.nio
     ByteBuffer]
    [org.jboss.netty.handler.codec.base64
     Base64]
    [org.jboss.netty.buffer
     ChannelBuffers
     ChannelBuffer
     CompositeChannelBuffer 
     ChannelBufferInputStream
     ByteBufferBackedChannelBuffer]))

;;;

(defn byte-array? [data]
  (and
    (.isArray (class data))
    (= (.getComponentType (class data)) Byte/TYPE)))

(defn channel-buffer? [buf]
  (instance? ChannelBuffer buf))

(defn channel-buffer->input-stream
  [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn channel-buffer->byte-buffer
  [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn channel-buffer->byte-buffers
  [^ChannelBuffer buf]
  (when buf
    (.toByteBuffers buf)))

(defn channel-buffer->string
  ([buf]
     (channel-buffer->string buf "UTF-8"))
  ([buf charset]
     (when buf
       (.toString ^ChannelBuffer buf ^String charset))))

(defn concat-channel-buffers [& bufs]
  (ChannelBuffers/wrappedBuffer (into-array ChannelBuffer (remove nil? bufs))))

(defn concat-byte-buffers [& bufs]
  (if (= 1 (count bufs))
    (first bufs)
    (let [size (apply + (map #(.remaining ^ByteBuffer %) bufs))
	  buf (ByteBuffer/allocate size)]
      (doseq [b bufs]
	(.put buf b))
      (.rewind buf))))

;;;

(defn byte-buffer->string
  ([buf]
     (byte-buffer->string buf "UTF-8"))
  ([^ByteBuffer buf charset]
     (when buf
       (let [ary (byte-array (.remaining buf))]
	 (.get buf ary)
	 (String. ary ^String charset)))))

(defn string->byte-buffer
  ([s]
     (string->byte-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ByteBuffer/wrap (.getBytes ^String s ^String charset)))))

;;;

(defn input-stream->byte-buffer
  [^InputStream stream]
  (when stream
    (let [available (.available stream)]
      (loop [ary ^bytes (byte-array (if (pos? available) available 1024)), offset 0, bufs []]
	(let [ary-len (count ary)]
	  (if (= ary-len offset)
	    (recur (byte-array 1024) 0 (conj bufs (ByteBuffer/wrap ary)))
	    (let [byte-count (.read stream ary offset (- ary-len offset))]
	      (if (neg? byte-count)
		(apply concat-byte-buffers (conj bufs (ByteBuffer/wrap ary 0 offset)))
		(recur ary (+ offset byte-count) bufs)))))))))

(defn byte-buffer->channel-buffer
  [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn input-stream->channel-buffer
  [stream]
  (-> stream input-stream->byte-buffer byte-buffer->channel-buffer))

(defn string->channel-buffer
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([s charset]
     (-> s (string->byte-buffer charset) byte-buffer->channel-buffer)))

(defn to-channel-buffer
  ([data]
     (to-channel-buffer data "utf-8"))
  ([data charset]
     (cond
       (instance? ChannelBuffer data) data
       (instance? ByteBuffer data) (byte-buffer->channel-buffer data)
       (instance? InputStream data) (input-stream->channel-buffer data)
       (instance? String data) (string->channel-buffer data charset)
       (byte-array? data) (-> data ByteBuffer/wrap byte-buffer->channel-buffer))))

(defn to-channel-buffer? [data]
  (or
    (instance? ChannelBuffer data)
    (instance? ByteBuffer data)
    (instance? InputStream data)
    (instance? String data)
    (byte-array? data)))

;;;

(defn base64-encode [string]
  (-> string
    string->channel-buffer
    (Base64/encode)
    channel-buffer->string))

(defn base64-decode [string]
  (-> string
    string->channel-buffer
    (Base64/decode)
    channel-buffer->string))

;;;

(defn data->json->channel-buffer [data]
  (let [output (ByteArrayOutputStream.)
	writer (PrintWriter. output)]
    (json/write-json data writer)
    (.flush writer)
    (-> output .toByteArray ByteBuffer/wrap byte-buffer->channel-buffer)))

(defn data->json->string [data]
  (-> data data->json->channel-buffer channel-buffer->string))

(defn channel-buffer->json->data [buf]
  (-> buf channel-buffer->input-stream InputStreamReader. (json/read-json-from true false nil)))

(defn string->json->data [s]
  (-> s string->channel-buffer channel-buffer->json->data))

;;;

(defn channel-buffer->xml->data [buf]
  (-> buf channel-buffer->input-stream xml/parse))

(defn string->xml->data
  ([s]
     (string->xml->data s "utf-8"))
  ([s charset]
     (-> s (string->channel-buffer charset) channel-buffer->input-stream xml/parse)))

(defn data->xml->string
  ([x]
     (data->xml->string x "utf-8"))
  ([x charset]
     (with-out-str
       (prxml/prxml [:decl! {:version "1.0" :encoding charset}])
       (cond
	 (vector? x) (prxml/prxml x)
	 (map? x) (xml/emit-element x)))))

(defn data->xml->channel-buffer
  ([x]
     (data->xml->channel-buffer x "utf-8"))
  ([x charset]
     (concat-channel-buffers
       (string->channel-buffer
	 (with-out-str (prxml/prxml [:decl! {:version "1.0" :encoding charset}]))
	 "ascii")
       (string->channel-buffer
	 (with-out-str
	   (cond
	     (vector? x) (prxml/prxml x)
	     (map? x) (xml/emit-element x)))
	 charset))))

;;;

