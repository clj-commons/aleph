;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Zachary Tellman"}
  aleph.formats
  (:use
    [lamina core])
  (:require
    [cheshire.core :as json]
    [clojure.xml :as xml]
    [clojure.contrib.prxml :as prxml])
  (:require
    [gloss.io :as gloss-io]
    [gloss.core :as gloss])
  (:import
    [java.io
     PipedInputStream
     PipedOutputStream
     IOException
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
     ChannelBufferInputStream
     ByteBufferBackedChannelBuffer]
    [java.security
     MessageDigest]))

;;;

(defn- byte-array?
  "Returns true if 'x' is a byte array."
  [x]
  (and
    x
    (.isArray (class x))
    (= (.getComponentType (class x)) Byte/TYPE)))

;;; ChannelBuffer to other formats

(defn channel-buffer? [x]
  (instance? ChannelBuffer x))

(defn channel-buffer->input-stream [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn channel-buffer->byte-buffer [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn channel-buffer->byte-buffers [^ChannelBuffer buf]
  (when buf
    (seq (.toByteBuffers buf))))

(defn channel-buffer->string
  ([buf]
     (channel-buffer->string buf "UTF-8"))
  ([buf charset]
     (when buf
       (.toString ^ChannelBuffer buf ^String charset))))

(defn channel-buffers->channel-buffer [bufs]
  (ChannelBuffers/wrappedBuffer
    ^{:tag "[Lorg.jboss.netty.buffer.ChannelBuffer;"}
    (into-array ChannelBuffer (remove nil? bufs))))

;;; Other formats into ChannelBuffer

(defn byte-buffer->channel-buffer [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn byte-buffers->channel-buffer [bufs]
  (ChannelBuffers/wrappedBuffer
    ^{:tag "[Ljava.nio.ByteBuffer;"}
    (into-array ByteBuffer bufs)))

(defn input-stream->channel-buffer [^InputStream stream]
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
  ([s charset]
     (-> (.getBytes ^String s ^String charset) ByteBuffer/wrap byte-buffer->channel-buffer)))

(defn- to-channel-buffer
  ([data]
     (to-channel-buffer data "utf-8"))
  ([data charset]
     (when data
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
	 (throw (Exception. (str "Cannot convert " (pr-str data) " to ChannelBuffer.")))))))

(defn- to-channel-buffer?
  [data]
  (or
    (nil? data)
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

(defn ^ChannelBuffer bytes->channel-buffer
  "Converts bytes into a Netty ChannelBuffer. By default, 'charset' is UTF-8."
  ([data]
     (bytes->channel-buffer data "utf-8"))
  ([data charset]
     (to-channel-buffer data charset)))

(defn ^InputStream bytes->input-stream
  "Converts bytes into an InputStream. By default, 'charset' is UTF-8."
  ([data]
     (bytes->input-stream data "utf-8"))
  ([data charset]
     (when data
       (if (instance? InputStream data)
	 data
	 (-> data (to-channel-buffer charset) channel-buffer->input-stream)))))

(defn ^ByteBuffer bytes->byte-buffer
  "Converts bytes into a ByteBuffer. By default, 'charset' is UTF-8.

   This may require copying multiple buffers into a single buffer. To avoid this,
   use bytes->byte-buffers."
  ([data]
     (bytes->byte-buffer data "utf-8"))
  ([data charset]
     (when data
       (if (instance? ByteBuffer data)
	 (.duplicate ^ByteBuffer data)
	 (-> data (to-channel-buffer charset) channel-buffer->byte-buffer)))))

(defn ^bytes bytes->byte-array
  "Convertes bytes into a byte array. By default, 'charset' is UTF-8."
  ([data]
     (bytes->byte-array data "utf-8"))
  ([data charset]
     (let [buf (bytes->byte-buffer data charset)
           ary (byte-array (.remaining buf))]
       (.get buf ary)
       ary)))

(defn bytes->byte-buffers
  "Converts bytes into a sequence of one or more ByteBuffers."
  ([data]
     (bytes->byte-buffers data "utf-8"))
  ([data charset]
     (when data
       (-> data (to-channel-buffer charset) channel-buffer->byte-buffers))))

(defn ^String bytes->string
  "Converts bytes to a string. By default, 'charset' is UTF-8."
  ([data]
     (bytes->string data "utf-8"))
  ([data charset]
     (when data
       (if (string? data)
	 data
	 (-> data (to-channel-buffer charset) (channel-buffer->string charset))))))

;;

(defn bytes->md5
  "Returns an MD5 hash for the bytes."
  [data]
  (->> data
    bytes->byte-array
    (.digest (MessageDigest/getInstance "md5"))))

(defn bytes->sha1
  "Returns an SHA1 hash for the bytes."
  [data]
  (->> data
    bytes->byte-array
    (.digest (MessageDigest/getInstance "sha1"))))

(defn base64-encode
  "Encodes the data into a base64 string representation."
  ([data]
     (base64-encode data true))
  ([data ^Boolean breaklines]
     (when data
       (-> data to-channel-buffer (Base64/encode breaklines) channel-buffer->string))))

(defn base64-decode
  "Decodes a base64 encoded string into bytes."
  [string]
  (when string
    (-> string string->channel-buffer Base64/decode)))

;;;

(defn decode-json
  "Takes bytes or a string that contain JSON, and returns a Clojure data structure representation."
  ([data]
     (decode-json data true))
  ([data keywordize?]
     (-> data bytes->string (json/parse-string keywordize?))))

(defn ^String encode-json->string
  "Transforms a Clojure data structure to JSON, and returns a string representation of the encoded data."
  [data]
  (-> data json/generate-string))

(defn encode-json->bytes
  "Transforms a Clojure data structure to JSON, and returns a byte representation of the encoded data."
  [data]
  (-> data encode-json->string (.getBytes "utf-8")))

;;;

(defn decode-xml
  "Takes bytes or a string that contains XML, and returns a Clojure hash representing the parsed data."
  ([data]
     (when data
       (if (string? data)
	 (let [[header charset] (re-seq #"^\<\?.*encoding=\"(.*)\".*\?\>" data)]
	   (decode-xml
	     [(string->channel-buffer header "ascii")
	      (string->channel-buffer
		(.substring ^String data (count header) (- (count data) (count header)))
		charset)]))
	 (-> data bytes->input-stream xml/parse)))))

(defn encode-xml->string
  "Takes a Clojure data structure representing a parse tree or prxml structure, and returns an XML string.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->string x "utf-8"))
  ([x charset]
     (when x
       (with-out-str
	 (prxml/prxml [:decl! {:version "1.0" :encoding charset}])
	 (cond
	   (vector? x) (prxml/prxml x)
	   (map? x) (xml/emit-element x))))))

(defn encode-xml->bytes
  "Takes a Clojure data structure representing a parse tree or prxml structure, and an XML representation as bytes.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->bytes x "utf-8"))
  ([x charset]
     (when x
       (channel-buffers->channel-buffer
	 [(string->channel-buffer
	    (with-out-str (prxml/prxml [:decl! {:version "1.0" :encoding charset}]))
	    "ascii")
	  (string->channel-buffer
	    (with-out-str
	      (cond
		(vector? x) (prxml/prxml x)
		(map? x) (xml/emit-element x)))
	    charset)]))))

;;;

(defn- sort-replace-map [m]
  (->> m
    (sort-by #(-> % key count))
    reverse))

(defn url-decode
  "Takes a URL-encoded string, and returns a standard representation of the string.
   By default, 'charset' is UTF-8.

   'options' may contain a :url-decodings hash, which maps encoded strings onto how they should
   be decoded.  For instance, the Java decoder doesn't recognize %99 as a trademark symbol, even
   though it's sometimes encoded that way.  To allow for this encoding, you can simply use:

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

   'options' may contain a :url-encodings hash, which can specify custom encodings for certain characters
    or substrings."
  ([s]
     (url-encode s "utf-8"))
  ([s charset]
     (url-encode s charset nil))
  ([s charset options]
     (let [s (reduce
	       (fn [s [from to]] (.replace ^String s (str from) ^String to))
	       s
	       (sort-replace-map (:url-encodings options)))]
       (URLEncoder/encode s charset))))

;;;

(defn- input-stream->channel-
  [ch ^InputStream stream chunk-size]
  (let [buffer? (and chunk-size (pos? chunk-size))
	chunk-size (if buffer? chunk-size 1024)
	create-array (if buffer?
		       #(byte-array chunk-size)
		       #(byte-array
			  (if (pos? (.available stream))
			    (.available stream)
			    1024)))]
    (loop [ary ^bytes (create-array), offset 0]
      (let [ary-len (count ary)]

	(if (= ary-len offset)

          ;; if we've filled the array, enqueue it and restart
	  (do
	    (enqueue ch (ByteBuffer/wrap ary))
	    (recur (create-array) 0))

          ;; read in as many bytes as we can
	  (let [byte-count (try
                             (.read stream ary offset (- ary-len offset))
                             (catch IOException e
                               -1))]

	    (if (neg? byte-count)

              ;; if we've reached the end, enqueue the last bytes and close
	      (if (zero? offset)
		(close ch)
		(enqueue-and-close ch (ByteBuffer/wrap ary 0 offset)))

	      (recur ary (+ offset byte-count)))))))))

(defn input-stream->channel
  "Converts an InputStream to a channel that emits bytes. Spawns a separate thread to read
   from the stream.

   If 'chunk-size' is specified, the channel will only emit byte sequences of the specified
   length, unless the stream is closed before a full chunk can be accumulated.  If it is nil
   or non-positive, bytes will be sent through the channel as soon as they enter the InputStream."
  ([stream]
     (input-stream->channel stream nil))
  ([stream chunk-size]
     (if-not stream
       (closed-channel)
       (let [ch (channel)]
	 (doto
	   (Thread. (fn [] (input-stream->channel- ch stream chunk-size)))
	   (.setName "InputStream reader")
	   .start)
	 ch))))

(defn channel->input-stream
  "Consumes messages from a channel that emits bytes, and feeds them into an InputStream.

   This does not create any threads.  If a blocking read on the InputStream is performed on
   a thread which is responsible for feeding messages into the channel, this will cause a
   deadlock."
  ([ch]
     (channel->input-stream ch "utf-8"))
  ([ch charset]
     (let [in (PipedInputStream.)
           out (PipedOutputStream. in)
           bytes (map* #(bytes->byte-array % charset) ch)]
       (receive-all bytes #(.write out %))
       (on-drained bytes #(.close out))
       in)))

;;;

(defn options->frame [{:keys [frame delimiters strip-delimiters?]
                       :or {strip-delimiters? true}}]
  (cond
    (and frame delimiters) (gloss/delimited-frame delimiters frame)
    (and frame (not delimiters)) (gloss/compile-frame frame)
    (and (not frame) delimiters) (gloss/delimited-block delimiters strip-delimiters?)
    :else nil))

(defn options->decoder [options]
  (-> options
    (update-in [:frame] #(or (:decoder options) %))
    options->frame))

(defn options->encoder [options]
  (-> options
    (update-in [:frame] #(or (:encoder options) %))
    options->frame))

(defn decode-channel [frame ch]
  (gloss-io/decode-channel
    (map* bytes->byte-buffers ch)
    (if (map? frame)
      (options->decoder frame)
      frame)))

(defn wrap-socket-channel [options ch]
  (let [encoder (options->encoder options)
        decoder (options->decoder options)
        ch* (channel)]
    (if encoder
      (join
        (->> ch*
          (map* #(gloss-io/encode encoder %))
          (map* bytes->channel-buffer))
        ch)
      (join
        (map* bytes->channel-buffer ch*)
        ch))
    (splice
      (if decoder
        (decode-channel decoder ch)
        ch)
      ch*)))
