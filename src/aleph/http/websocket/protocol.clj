;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket.protocol
  (:use
    [gloss core io]
    [lamina core]
    [aleph formats]
    [clojure.contrib.def :only (defn-memo)])
  (:import
    [java.nio
     ByteBuffer]))

(defcodec header-codec
  (bit-map
    :fin 1
    :rsv1 1
    :rsv2 1
    :rsv3 1
    :opcode 4
    :mask 1
    :length 7))

(def opcode->msg-type
  {0 :continuation
   1 :text
   2 :binary
   8 :close
   9 :ping
   10 :pong})

(def msg-type->opcode
  (zipmap (vals opcode->msg-type) (keys opcode->msg-type)))

(defn-memo body-codec
  [mask? length]
  (let [basic-frame (case length
		      :short (finite-block :uint16)
		      :long (finite-block :uint64))]
    (compile-frame
      (if-not mask?
	{:data basic-frame}
	(ordered-map
	  :mask :int32
	  :data basic-frame)))))

(defcodec websocket-frame
  (compile-frame
    (header header-codec
      
      (fn [{:keys [fin opcode mask length]}]
	(case length
	  126 (body-codec mask :short)
	  127 (body-codec mask :long)
	  (compile-frame
	    (if-not mask
	      {:data (finite-block length)}
	      (ordered-map
		:mask :int32
		:data (finite-block length))))))

      (fn [{:keys [data mask type final?]}]
	(let [byte-length (byte-count data)]
	  {:fin (or final? true)
	   :rsv1 false
	   :rsv2 false
	   :rsv3 false
	   :opcode (msg-type->opcode (or type :binary))
	   :mask (boolean mask)
	   :length (if (< byte-length 126)
		     byte-length
		     127)})))))

(defn mask->byte-array [mask]
  (->> [24 16 8 0]
    (map #(-> mask int (bit-and (bit-shift-left 0xFF %)) (bit-shift-right %) (bit-and 0xFF)))
    (map #(.byteValue (Short. (short %))))
    byte-array))

(defn mask-buffer [^bytes mask ary-idx ^ByteBuffer buf]
  (let [limit (dec (.remaining buf))]
    (loop [idx 0, ary-idx ary-idx]
      (let [val (.get buf idx)]
	(.put buf idx (bit-xor val (aget mask ary-idx)))
	(if (= limit idx)
	  ary-idx
	  (recur (inc idx) (rem (inc ary-idx) 4)))))))

(defn mask-buffers
  "Does an in-place masking of a sequence of ByteBuffers."
  [mask bufs]
  (let [mask (mask->byte-array mask)]
    (loop [bufs bufs, ary-idx 0]
      (when-not (empty? bufs)
	(let [ary-idx (mask-buffer mask ary-idx (first bufs))]
	  (recur (rest bufs) ary-idx))))
    bufs))

(defn pre-process-frame [frame]
  (update-in frame [:data]
    #(if-let [mask (:mask frame)]
       (mask-buffers mask %)
       %)))

(defn post-process-frame [frame]
  (case (:type frame)
    :binary (:data frame)
    :text (bytes->string (:data frame))
    (bytes->string (:data frame))))

(defn encode-frame [data]
  (encode websocket-frame
    (cond

      (map? data)
      data

      (string? data)
      {:type :text
       :data (bytes->byte-buffers data)}

      :else
      {:type :binary
       :data (bytes->byte-buffers data)})))

(defn wrap-websocket-channel [src]
  (let [dst (channel)
	frames (decode-channel src websocket-frame)]
    (run-pipeline nil
      :error-handler (fn [_] (close dst))
      (read-merge #(read-channel frames)
	(fn [old-frame new-frame]
	  (let [new-frame (pre-process-frame new-frame)]
	    (enqueue dst (post-process-frame new-frame)))))
      (fn [_]
	(restart)))
    (on-closed dst #(close frames))
    dst))





