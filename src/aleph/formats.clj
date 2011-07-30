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

(defn- byte-array?
  "Returns true if 'x' is a byte array."
  [x]
  (and
    x
    (.isArray (class x))
    (= (.getComponentType (class x)) Byte/TYPE)))

;;; ChannelBuffer to other formats

(defn- channel-buffer? [x]
  (instance? ChannelBuffer x))

(defn- channel-buffer->input-stream [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn- channel-buffer->byte-buffer [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn- channel-buffer->byte-buffers [^ChannelBuffer buf]
  (when buf
    (seq (.toByteBuffers buf))))

(defn- channel-buffer->string
  ([buf]
     (channel-buffer->string buf "UTF-8"))
  ([buf charset]
     (when buf
       (.toString ^ChannelBuffer buf ^String charset))))

(defn- channel-buffers->channel-buffer [bufs]
  (ChannelBuffers/wrappedBuffer (into-array ChannelBuffer (remove nil? bufs))))

;;; Other formats into ChannelBuffer

(defn- byte-buffer->channel-buffer [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn- byte-buffers->channel-buffer [bufs]
  (ChannelBuffers/wrappedBuffer (into-array ByteBuffer bufs)))

(defn- input-stream->channel-buffer [^InputStream stream]
  (when stream
    (let [available (.available stream)]
      (loop [ary ^bytes (byte-array (if (pos? available) available 1024)), offset 0, bufs []]
	(let [ary-len (count ary)]
	  (if (= ary-len offset)
	    (recur (byte-array 1024) 0 (conj bufs (ByteBuffer/wrap ary)))
	    (let [byte-count (.read stream ary offset (- ary-len offset))]
	      (if (neg? byte-count)
		(byte-buffers->channel-buffer (conj bufs (ByteBuffer/wrap ary 0 offset)))
		(recur ary (+ offset byte-count) bufs)))))))))

(defn- string->channel-buffer
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([^String s charset]
     (-> s (.getBytes charset) ByteBuffer/wrap byte-buffer->channel-buffer)))

(defn- to-channel-buffer
  ([data]
     (to-channel-buffer data "utf-8"))
  ([data charset]
     (cond
       (instance? ChannelBuffer data)
       data

       (instance? ByteBuffer data)
       (byte-buffer->channel-buffer data)

       (instance? InputStream data)
       (input-stream->channel-buffer data)

       (instance? String data)
       (string->channel-buffer data charset)

       (byte-array? data)
       (-> data ByteBuffer/wrap byte-buffer->channel-buffer)

       (and (sequential? data) (every? #(instance? ByteBuffer %) data))
       (byte-buffers->channel-buffer data)

       (and (sequential? data) (every? #(instance? ChannelBuffer %) data))
       (channel-buffers->channel-buffer data)

       :else
       (throw (Exception. (str "Cannot convert " (pr-str data) " to ChannelBuffer."))))))

(defn- to-channel-buffer?
  [data]
  (or
    (instance? ChannelBuffer data)
    (instance? ByteBuffer data)
    (instance? InputStream data)
    (instance? String data)
    (byte-array? data)
    (and (sequential? data) (every? #(instance? ByteBuffer %) data))
    (and (sequential? data) (every? #(instance? ChannelBuffer %) data))))

;;;

(defn bytes?
  "Returns true if 'x' is a valid byte format. Valid byte formats are:

   Netty ChannelBuffers
   ByteBuffers
   InputStreams
   Strings
   Byte arrays
   Sequences of ByteBuffers
   Sequences of ChannelBuffers"
  [x]
  (to-channel-buffer? x))

(defn bytes->channel-buffer
  "Converts bytes into a Netty ChannelBuffer. By default, 'charset' is UTF-8."
  ([data]
     (bytes->channel-buffer data "utf-8"))
  ([data charset]
     (to-channel-buffer data charset)))

(defn bytes->input-stream
  "Converts bytes into an InputStream. By default, 'charset' is UTF-8."
  ([data]
     (bytes->input-stream data "utf-8"))
  ([data charset]
     (if (instance? InputStream data)
       data
       (-> data (to-channel-buffer charset) channel-buffer->input-stream))))

(defn bytes->byte-buffer
  "Converts bytes into a ByteBuffer. By default, 'charset' is UTF-8.

   This may require copying multiple buffers into a single buffer. To avoid this,
   use bytes->byte-buffers."
  ([data]
     (bytes->byte-buffer data "utf-8"))
  ([data charset]
     (if (instance? ByteBuffer data)
       data
       (-> data (to-channel-buffer charset) channel-buffer->byte-buffer))))

(defn bytes->byte-buffers
  "Converts bytes into a sequence of one or more ByteBuffers."
  ([data]
     (bytes->byte-buffers data "utf-8"))
  ([data charset]
     (-> data (to-channel-buffer charset) channel-buffer->byte-buffers)))

(defn bytes->string
  "Converts bytes to a string. By default, 'charset' is UTF-8."
  ([data]
     (bytes->string data "utf-8"))
  ([data charset]
     (if (string? data)
       data
       (-> data (to-channel-buffer charset) (channel-buffer->string charset)))))

;;;

(defn base64-encode
  "Encodes the data into a base64 string representation."
  [data]
  (-> data to-channel-buffer Base64/encode channel-buffer->string))

(defn base64-decode
  "Decodes a base64 encoded string into bytes."
  [string]
  (-> string string->channel-buffer Base64/decode))

;;;

(defn decode-json
  "Takes bytes or a string that contain JSON, and returns a Clojure data structure representation."
  [data]
  (-> data bytes->input-stream InputStreamReader. (json/read-json-from true false nil)))

(defn encode-json->bytes
  "Transforms a Clojure data structure to JSON, and returns a byte representation of the encoded data."
  [data]
  (let [output (ByteArrayOutputStream.)
	writer (PrintWriter. output)]
    (json/write-json data writer)
    (.flush writer)
    (-> output .toByteArray to-channel-buffer)))

(defn encode-json->string
  "Transforms a Clojure data structure to JSON, and returns a string representation of the encoded data."
  [data]
  (-> data encode-json->bytes bytes->string))

;;;

(defn decode-xml
  "Takes bytes or a string that contains XML, and returns a Clojure hash representing the parsed data."
  ([data]
     (if (string? data)
       (let [[header charset] (re-seq #"^\<\?.*encoding=\"(.*)\".*\?\>" data)]
	 (decode-xml
	   [(string->channel-buffer header "ascii")
	    (string->channel-buffer
	      (.substring ^String data (count header) (- (count data) (count header)))
	      charset)]))
       (-> data bytes->input-stream xml/parse))))

(defn encode-xml->string
  "Takes a Clojure data structure representing a parse tree or prxml structure, and returns an XML string.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->string x "utf-8"))
  ([x charset]
     (with-out-str
       (prxml/prxml [:decl! {:version "1.0" :encoding charset}])
       (cond
	 (vector? x) (prxml/prxml x)
	 (map? x) (xml/emit-element x)))))

(defn encode-xml->bytes
  "Takes a Clojure data structure representing a parse tree or prxml structure, and an XML representation as bytes.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->bytes x "utf-8"))
  ([x charset]
     (channel-buffers->channel-buffer
       [(string->channel-buffer
	  (with-out-str (prxml/prxml [:decl! {:version "1.0" :encoding charset}]))
	  "ascii")
	(string->channel-buffer
	  (with-out-str
	    (cond
	      (vector? x) (prxml/prxml x)
	      (map? x) (xml/emit-element x)))
	  charset)])))

;;;

(defn- sort-replace-map [m]
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
	       (fn [s [from to]] (.replace ^String s ^String (str from) ^String to))
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
