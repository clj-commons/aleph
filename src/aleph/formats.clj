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
    [lamina core]
    [clojure.data.xml :only [sexp-as-element emit]])
  (:require
    [cheshire.core :as json]
    [clojure.xml :as xml]
    [gloss.io :as gloss-io]
    [gloss.core :as gloss]
    [clojure.tools.logging :as log])
  (:import
    [java.util.zip
     Deflater
     Inflater]
    [java.io
     PipedInputStream
     PipedOutputStream
     IOException
     InputStream
     InputStreamReader
     PrintWriter
     ByteArrayOutputStream]
    [org.apache.commons.codec.net
     URLCodec]
    [org.apache.commons.codec.binary
     Base64]
    [org.apache.commons.compress.compressors.gzip
     GzipCompressorInputStream
     GzipCompressorOutputStream]
    [java.net
     URLEncoder
     URLDecoder]
    [java.nio
     ByteBuffer]
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
     (-> (.getBytes ^String s ^String (name charset)) ByteBuffer/wrap byte-buffer->channel-buffer)))

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

(defn bytes->byte-array
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
     (base64-encode data false))
  ([data ^Boolean url-safe?]
     (when data
       (let [encoder (Base64. -1 (byte-array 0) url-safe?)]
         (->> data bytes->byte-array ^bytes (.encode encoder) String.)))))

(defn base64-decode
  "Decodes a base64 encoded string into bytes."
  [^String string]
  (when string
    (let [decoder (Base64.)]
      (.decode decoder string))))

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

(defn- emit-element-with-declaration
  [x charset]
  (print "<?xml version=\"1.0\" encoding=\"")
  (print charset)
  (print "\"?>")
  (xml/emit-element x))

(defn encode-xml->string
  "Takes a Clojure data structure representing a parse tree or prxml/hiccup-style data structure,
   and returns an XML string.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->string x "utf-8"))
  ([x charset]
     (when x
       (cond
         (vector? x) (with-out-str (emit (sexp-as-element x) *out* :encoding charset))
         (map? x) (with-out-str (emit-element-with-declaration x charset))))))

(defn encode-xml->bytes
  "Takes a Clojure data structure representing a parse tree or prxml/hiccup-style data structure,
   and returns an XML representation as bytes.

   By default, 'charset' is UTF-8."
  ([x]
     (encode-xml->bytes x "utf-8"))
  ([x charset]
     (string->channel-buffer (encode-xml->string x charset))))

;;;

(defn url-decode
  "Takes a URL-encoded string, and returns a standard representation of the string.
   By default, 'charset' is UTF-8."
  ([s]
     (url-decode s "utf-8"))
  ([^String s charset]
     (let [decoder (URLCodec. charset)]
       (.decode decoder s))))

(defn url-encode
  "URL-encodes a string.  By default 'charset' is UTF-8."
  ([s]
     (url-encode s "utf-8"))
  ([s charset]
     (let [encoder (URLCodec. charset)]
       (.encode encoder s))))

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
                             (catch IOException _
                               -1))]

	    (if (neg? byte-count)

              ;; if we've reached the end, enqueue the last bytes and close
	      (if (zero? offset)
		(close ch)
		(enqueue-and-close ch (ByteBuffer/wrap ary 0 offset)))

	      (recur ary (long (+ offset byte-count))))))))))

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
  "Consumes messages from a channel that emits bytes, and feeds them into an InputStream."
  ([ch]
     (channel->input-stream ch "utf-8"))
  ([ch charset]
     (let [out (PipedOutputStream.)
           in (PipedInputStream. out 16384)
           bytes (map* #(bytes->byte-array % charset) ch)]

       (future
         (try
           (loop []
             (when-let [msg @(read-channel* bytes :on-drained nil)]
               (.write out ^bytes msg)
               (recur)))
           (finally
             (.close out))))
              
       in)))

(defn bytes->channel
  "Returns a channel that emits ChannelBuffers."
  ([data]
     (bytes->channel data "utf-8"))
  ([data charset]
     (cond
       (channel? data)
       (map* bytes->channel-buffer data)
       
       (instance? InputStream data)
       (input-stream->channel data)

       :else
       (closed-channel (bytes->channel-buffer data charset)))))

;;;

(defn gzip-bytes
  "Compresses bytes using gzip. Returns an InputStream."
  ([bytes]
     (gzip-bytes bytes "utf-8"))
  ([bytes charset]
     (let [out (PipedOutputStream.)
           in (PipedInputStream. out)
           compressor (GzipCompressorOutputStream. out)
           ch (bytes->channel bytes charset)
           bytes (map* bytes->byte-array ch)]

       (future
         (try
           (loop []
             (when-let [msg @(read-channel* bytes :on-drained nil)]
               (.write compressor ^bytes msg)
               (recur)))
           (finally
             (.close compressor))))

       in)))

(defn ungzip-bytes
  "Decompresses bytes that were processed using 'gzip-bytes'.  Returns an InputStream."
  ([bytes]
     (ungzip-bytes bytes "utf-8"))
  ([bytes charset]
     (-> bytes (bytes->input-stream charset) GzipCompressorInputStream.)))

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
