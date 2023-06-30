(ns ^:no-doc aleph.http.file
  (:require
    [clj-commons.byte-streams :as bs]
    [clj-commons.byte-streams.graph :as g]
    [clojure.java.io :as io]
    [manifold.stream :as s])
  (:import
    (java.io
      File
      RandomAccessFile)
    (java.nio
      ByteBuffer)
    (java.nio.channels
      FileChannel
      FileChannel$MapMode)
    (java.nio.file
      Path)))

(def ^:dynamic *default-file-chunk-size* 16384)

(deftype HttpFile [^File fd ^long offset ^long length ^long chunk-size])

(defmethod print-method HttpFile [^HttpFile file ^java.io.Writer w]
  (.write w (format "HttpFile[fd:%s offset:%s length:%s]"
                    (.-fd file)
                    (.-offset file)
                    (.-length file))))

(defn http-file
  ([path]
   (http-file path nil nil *default-file-chunk-size*))
  ([path offset length]
   (http-file path offset length *default-file-chunk-size*))
  ([path offset length chunk-size]
   (let [^File
         fd (cond
              (string? path)
              (io/file path)

              (instance? File path)
              path

              (instance? Path path)
              (.toFile ^Path path)

              :else
              (throw
                (IllegalArgumentException.
                  (str "cannot conver " (class path) " to file, "
                       "expected either string, java.io.File "
                       "or java.nio.file.Path"))))
         region? (or (some? offset) (some? length))]
     (when-not (.exists fd)
       (throw
         (IllegalArgumentException.
           (str fd " file does not exist"))))

     (when (.isDirectory fd)
       (throw
         (IllegalArgumentException.
           (str fd " is a directory, file expected"))))

     (when (and region? (not (<= 0 offset)))
       (throw
         (IllegalArgumentException.
           "offset of the region should be 0 or greater")))

     (when (and region? (not (pos? length)))
       (throw
         (IllegalArgumentException.
           "length of the region should be greater than 0")))

     (let [len (.length fd)
           [p c] (if region?
                   [offset length]
                   [0 len])
           chunk-size (or chunk-size *default-file-chunk-size*)]
       (when (and region? (< len (+ offset length)))
         (throw
           (IllegalArgumentException.
             "the region exceeds the size of the file")))

       (HttpFile. fd p c chunk-size)))))

(defn http-file->stream
  [^HttpFile file]
  (-> file
      (bs/to-byte-buffers {:chunk-size (.-chunk-size file)})
      s/->source))

(bs/def-conversion ^{:cost 0} [HttpFile (bs/seq-of ByteBuffer)]
                   [file {:keys [chunk-size writable?]
                          :or {chunk-size (int *default-file-chunk-size*)
                               writable? false}}]
                   (let [^RandomAccessFile raf (RandomAccessFile. ^File (.-fd file)
                                                                  (if writable? "rw" "r"))
                         ^FileChannel fc (.getChannel raf)
                         end-offset (+ (.-offset file) (.-length file))
                         buf-seq (fn buf-seq [offset]
                                   (when-not (<= end-offset offset)
                                     (let [remaining (- end-offset offset)]
                                       (lazy-seq
                                         (cons
                                           (.map fc
                                                 (if writable?
                                                   FileChannel$MapMode/READ_WRITE
                                                   FileChannel$MapMode/READ_ONLY)
                                                 offset
                                                 (min remaining chunk-size))
                                           (buf-seq (+ offset chunk-size)))))))]
                     (g/closeable-seq
                       (buf-seq (.-offset file))
                       false
                       #(do
                          (.close raf)
                          (.close fc)))))

