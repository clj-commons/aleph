(ns aleph.http.multipart
  (:require
    [byte-streams :as bs])
  (:import
    [java.util
     Locale]
    [java.io
     File]
    [java.net
     URLConnection]
    [io.netty.util.internal
     ThreadLocalRandom]))

(defn boundary []
  (-> (ThreadLocalRandom/current) .nextLong Long/toHexString .toLowerCase))

(defn mime-type-descriptor
  [^String mime-type ^String encoding]
  (str
    (-> (or mime-type "application/octet-stream") .trim (.toLowerCase Locale/US))
    (when encoding
      (str ";charset=" encoding))))

(defn populate-part
  [{:keys [name content mime-type encoding]}]
  (if (instance? File content)
    {:name name
     :content (bs/to-byte-buffer content)
     :mime-type (mime-type-descriptor
                  (or mime-type (URLConnection/guessContentTypeFromName (.getName ^File content))))}

    ))


(defn multipart-body [boundary parts]
  )
