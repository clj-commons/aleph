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
     InputStream]
    [java.nio
     ByteBuffer]
    [org.jboss.netty.handler.codec.base64
     Base64]
    [org.jboss.netty.buffer
     ChannelBuffers
     ChannelBuffer
     ChannelBufferInputStream
     ByteBufferBackedChannelBuffer]))

(defn input-stream->channel-buffer
  [^InputStream stream]
  (when stream
    (let [ary (make-array Byte/TYPE (.available stream))]
      (.read stream ary)
      (ChannelBuffers/wrappedBuffer ary))))

(defn channel-buffer->input-stream
  [^ChannelBuffer buf]
  (when buf
    (ChannelBufferInputStream. buf)))

(defn byte-buffer->channel-buffer
  [^ByteBuffer buf]
  (when buf
    (ByteBufferBackedChannelBuffer. buf)))

(defn channel-buffer->byte-buffer
  [^ChannelBuffer buf]
  (when buf
    (.toByteBuffer buf)))

(defn byte-buffer->string
  ([buf]
     (byte-buffer->string buf "ASCII"))
  ([^ByteBuffer buf charset]
     (when buf
       (let [ary (byte-array (.remaining buf))]
	 (.get buf ary)
	 (String. ary charset)))))

(defn string->byte-buffer
  ([s]
     (string->byte-buffer s "ASCII"))
  ([^String s charset]
     (when s
       (ByteBuffer/wrap (.getBytes s charset)))))

;;;

(defn base64-encode [string]
  (-> string
    string->byte-buffer
    byte-buffer->channel-buffer
    (Base64/encode)
    channel-buffer->byte-buffer
    byte-buffer->string))

(defn base64-decode [string]
  (-> string
    string->byte-buffer
    byte-buffer->channel-buffer
    (Base64/decode)
    channel-buffer->byte-buffer
    byte-buffer->string))
