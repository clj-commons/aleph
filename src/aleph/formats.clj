;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
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
    [java.net
     URLEncoder
     URLDecoder]
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

(defn byte-array?
  "Returns true if 'x' is a byte array."
  [x]
  (and
    (.isArray (class x))
    (= (.getComponentType (class x)) Byte/TYPE)))

(defn channel-buffer?
  "Returns true if 'x' is a Netty ChannelBuffer."
  [x]
  (instance? ChannelBuffer x))

(defn channel-buffer->input-stream
  "Transforms a Netty ChannelBuffer to an InputStream."
  [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn channel-buffer->byte-buffer
  "Transforms a Netty ChannelBuffer to a ByteBuffer.  This may involve concatenating several ByteBuffers together,
   to avoid this use channel-buffer->byte-buffers instead."
  [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn channel-buffer->byte-buffers
  "Transforms a Netty ChannelBuffer into a sequence of one or more ByteBuffers."
  [^ChannelBuffer buf]
  (when buf
    (seq (.toByteBuffers buf))))

(defn byte-buffers->channel-buffer
  "Transforms a sequence of ByteBuffers into a Netty ChannelBuffer."
  [bufs]
  (ChannelBuffers/wrappedBuffer (into-array ByteBuffer (remove nil? bufs))))

(defn channel-buffer->string
  "Transforms a Netty ChannelBuffer into a string.  By default, 'charset' is UTF-8."
  ([buf]
     (channel-buffer->string buf "UTF-8"))
  ([buf charset]
     (when buf
       (.toString ^ChannelBuffer buf ^String charset))))

(defn concat-channel-buffers
  "Takes one or more Netty ChannelBuffers, and returns a ChannelBuffer representing them as a contiguous byte sequence.
   This does not require any copies."
  [& bufs]
  (ChannelBuffers/wrappedBuffer (into-array ChannelBuffer (remove nil? bufs))))

(defn concat-byte-buffers
  "Takes one or more ByteBuffers, and returns a ByteBuffer representing them as a contiguous sequence.  When more than one
   buffer is passed in, this requires copying all buffers onto a new buffer."
  [& bufs]
  (if (= 1 (count bufs))
    (.duplicate ^ByteBuffer (first bufs))
    (let [size (apply + (map #(.remaining ^ByteBuffer %) bufs))
	  buf (ByteBuffer/allocate size)]
      (doseq [b bufs]
	(.put buf b))
      (.rewind buf))))

;;;

(defn byte-buffer->string
  "Transforms a ByteBuffer into a string.  By default, 'charset' is UTF-8."
  ([buf]
     (byte-buffer->string buf "UTF-8"))
  ([^ByteBuffer buf charset]
     (when buf
       (let [ary (byte-array (.remaining buf))]
	 (.get buf ary)
	 (String. ary ^String charset)))))

(defn string->byte-buffer
  "Transforms a string into a ByteBuffer.  By default, 'charset' is UTF-8."
  ([s]
     (string->byte-buffer s "UTF-8"))
  ([s charset]
     (when s
       (ByteBuffer/wrap (.getBytes ^String s ^String charset)))))

;;;

(defn input-stream->byte-buffer
  "Transforms an InputStream into a ByteBuffer.  If the stream isn't closed, this function will
   block until it has been closed."
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
  "Transforms a ByteBuffer into a Netty ChannelBuffer."
  [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn byte-buffers->channel-buffer
  [bufs]
  (ChannelBuffers/wrappedBuffer (into-array ByteBuffer bufs)))

(defn input-stream->channel-buffer
  "Transforms an InputStream into a Netty ChannelBuffer.  If the stream isn't closed, this function
   will block until it has been closed."
  [stream]
  (-> stream input-stream->byte-buffer byte-buffer->channel-buffer))

(defn string->channel-buffer
  "Transforms a string into a Netty ChannelBuffer.  By default, 'charset' is UTF-8."
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([s charset]
     (-> s (string->byte-buffer charset) byte-buffer->channel-buffer)))

(defn to-channel-buffer
  "Transforms the data into a Netty ChannelBuffer. 'charset' is only used when the data is a string, and
   by default is UTF-8."
  ([data]
     (to-channel-buffer data "utf-8"))
  ([data charset]
     (cond
       (instance? ChannelBuffer data) data
       (instance? ByteBuffer data) (byte-buffer->channel-buffer data)
       (instance? InputStream data) (input-stream->channel-buffer data)
       (instance? String data) (string->channel-buffer data charset)
       (and (sequential? data) (every? #(instance? ByteBuffer %) data)) (byte-buffers->channel-buffer data)
       (byte-array? data) (-> data ByteBuffer/wrap byte-buffer->channel-buffer))))

(defn to-channel-buffer?
  "Returns true if the data can be transformed to a Netty ChannelBuffer."
  [data]
  (or
    (instance? ChannelBuffer data)
    (instance? ByteBuffer data)
    (instance? InputStream data)
    (instance? String data)
    (and (sequential? data) (every? #(instance? ByteBuffer %) data))
    (byte-array? data)))

;;;

(defn base64-encode
  "Encodes the string into a base64 representation."
  [string]
  (-> string string->channel-buffer Base64/encode channel-buffer->string))

(defn base64-decode
  "Decodes a base64 encoded string."
  [string]
  (-> string string->channel-buffer Base64/decode channel-buffer->string))

;;;

(defn data->json->channel-buffer
  "Transforms a Clojure data structure to JSON, and returns a Netty ChannelBuffer representing the encoded data."
  [data]
  (let [output (ByteArrayOutputStream.)
	writer (PrintWriter. output)]
    (json/write-json data writer)
    (.flush writer)
    (-> output .toByteArray ByteBuffer/wrap byte-buffer->channel-buffer)))

(defn data->json->string
  "Transforms a Clojure data structure to JSON, and returns a string representation of the encoded data."
  [data]
  (-> data data->json->channel-buffer channel-buffer->string))

(defn channel-buffer->json->data
  "Takes a Netty ChannelBuffer containing JSON, and returns a Clojure data structure representation."
  [buf]
  (-> buf channel-buffer->input-stream InputStreamReader. (json/read-json-from true false nil)))

(defn string->json->data
  "Takes a string representation of JSON, and returns it as a Clojure data structure."
  [s]
  (-> s string->channel-buffer channel-buffer->json->data))

;;;

(defn channel-buffer->xml->data
  "Takes a Netty ChannelBuffer containing XML, and returns a Clojure hash representing the parsed data."
  [buf]
  (-> buf channel-buffer->input-stream xml/parse))

(defn string->xml->data
  "Takes an XML string, and returns a Clojure hash representing the parsed data."
  ([s]
     (string->xml->data s "utf-8"))
  ([s charset]
     (-> s (string->channel-buffer charset) channel-buffer->input-stream xml/parse)))

(defn data->xml->string
  "Takes a Clojure data structure representing a parse tree or prxml structure, and returns an XML string.
   By default, 'charset' is UTF-8."
  ([x]
     (data->xml->string x "utf-8"))
  ([x charset]
     (with-out-str
       (prxml/prxml [:decl! {:version "1.0" :encoding charset}])
       (cond
	 (vector? x) (prxml/prxml x)
	 (map? x) (xml/emit-element x)))))

(defn data->xml->channel-buffer
  "Takes a Clojure data structure representing a parse tree or prxml structure, and returns a Netty ChannelBuffer.
   By default, 'charset' is UTF-8."
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

(defn sort-replace-map [m]
  (->> m
    (sort-by #(-> % key count))
    reverse))

(defn url-decode
  "Takes a URL-encoded string, and returns a standard representation of the string.  By default, 'charset' is UTF-8.

    'options' may contain a :url-decodings hash, which maps encoded strings onto how they should be decoded.  For instance,
    the Java decoder doesn't recognize %99 as a trademark symbol, even though it's sometimes encoded that way.  To allow
    for this encoding, you can simply use:

    (url-decode s \"utf-8\" {:url-decodings {\"%99\" \"\\u2122\"}})"
  ([s]
     (url-decode s "utf-8"))
  ([s charset]
     (url-decode s charset nil))
  ([s charset options]
     (let [s (reduce
	       (fn [s [from to]] (.replace ^String s (str from) to))
	       s
	       (sort-replace-map (:url-decodings options)))]
       (URLDecoder/decode s charset))))

(defn url-encode
  "URL-encodes a string.  By default 'charset' is UTF-8.

   'options' may contain a :url-encodings hash, which can specify custom encodings for certain characters or substrings."
  ([s]
     (url-encode s "utf-8"))
  ([s charset]
     (url-encode s charset nil))
  ([s charset options]
     (let [s (reduce
	       (fn [s [from to]] (.replace ^String s (str from) to))
	       s
	       (sort-replace-map (:url-encodings options)))]
       (URLEncoder/encode s charset))))
