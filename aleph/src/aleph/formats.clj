;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.formats
  (:use
    [clojure.contrib.json])
  (:import
    [java.io
     InputStream
     PrintWriter
     ByteArrayOutputStream]
    [java.nio
     ByteBuffer]
    [org.jboss.netty.handler.codec.base64
     Base64]
    [org.jboss.netty.buffer
     ChannelBuffers
     ChannelBuffer
     ChannelBufferInputStream
     ByteBufferBackedChannelBuffer]))

;;;

(defn channel-buffer->input-stream
  [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn channel-buffer->byte-buffer
  [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn channel-buffer->string
  ([buf]
     (channel-buffer->string buf "UTF-8"))
  ([^ChannelBuffer buf charset]
     (when buf
       (.toString buf charset))))

;;;

(defn byte-buffer->string
  ([buf]
     (byte-buffer->string buf "UTF-8"))
  ([^ByteBuffer buf charset]
     (when buf
       (let [ary (byte-array (.remaining buf))]
	 (.get buf ary)
	 (String. ary charset)))))

(defn string->byte-buffer
  ([s]
     (string->byte-buffer s "UTF-8"))
  ([^String s charset]
     (when s
       (ByteBuffer/wrap (.getBytes s charset)))))

;;;

(defn input-stream->channel-buffer
  [^InputStream stream]
  (when stream
    (let [ary (make-array Byte/TYPE (.available stream))]
      (.read stream ary)
      (ChannelBuffers/wrappedBuffer ary))))

(defn byte-buffer->channel-buffer
  [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn string->channel-buffer
  ([s]
     (string->channel-buffer s "UTF-8"))
  ([s charset]
     (-> s (string->byte-buffer charset) byte-buffer->channel-buffer)))

(defn to-channel-buffer [data]
  (cond
    (instance? ChannelBuffer data) data
    (instance? ByteBuffer data) (byte-buffer->channel-buffer data)
    (instance? InputStream data) (input-stream->channel-buffer data)
    (instance? String data) (string->channel-buffer data)))

(defn to-channel-buffer? [data]
  (or
    (instance? ChannelBuffer data)
    (instance? ByteBuffer data)
    (instance? InputStream data)
    (instance? String data)))

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

(defn from-json [data]
  (read-json-from data true false nil))

(defn to-json [x]
  (let [output (ByteArrayOutputStream.)
	writer (PrintWriter. output)]
    (write-json x writer)
    (.flush writer)
    (-> output .toByteArray ByteBuffer/wrap)))
