# Handling multipart requests

## Defining the handler

Aleph allows you to handle multipart requests through the usage of the `aleph.http.multipart/decode-request`
function. This function returns a `manifold.stream` of parts having the following
specification :

```clojure
(s/def ::part-name         string?)
(s/def ::content           (s/or :string       string?
                                 :input-stream (partial instance? java.io.InputStream)))
(s/def ::name              (s/nilable string?))
(s/def ::charset           string?)
(s/def ::mime-type         string?)
(s/def ::transfer-encoding (s/nilable string?))
(s/def ::memory?           boolean?)
(s/def ::file?             boolean?)
(s/def ::file              (s/nilable (partial instance? java.io.File)))
(s/def ::size              int?)

(s/def ::part (s/keys
               :req-un [::part-name
                        ::content
                        ::name
                        ::charset
                        ::mime-type
                        ::transfer-encoding
                        ::memory?
                        ::file?
                        ::file
                        ::size]))
```

Every file can be stored either on memory or on the filesystem. It depends on the
`memory-limit` passed to `decode-request` which defaults to 16 KB.

By default, all the resources are automatically cleaned up as soon as the stream is closed.
It means that all the temporary files created on the filesystem will be removed.

```clojure
(require '[aleph.http.multipart :as mp])
(require '[clojure.java.io      :as io])
(require '[manifold.stream      :as s])

(defn multipart-handler [req]
  (let [s (mp/decode-request req {:memory-limit (* 16 1024)})]
    (doseq [part (s/stream->seq s)]
      (if (:memory? part))
        (:content part)                           ;; do what you want with the content
        (io/copy (:file part) (io/file "..."))))) ;; copy the file on another location
```

## Limitations

As the consumption of the stream does not happen on the same Thread that cleans the
resources, the files might be removed before you have the time to copy them on
another location.
If you want to rely on that convenient mechanism, you need to ensure all the parts
you want to handle will fit on memory by overriding the default value.
Depending on your workload, having a value between `8-100` MiB might be affordable.

```clojure
(mp/decode-request req {:memory-limit (* 16 1024 1024)})
```

## Manual cleanup

If you cannot afford that much data on memory and you want to rely on temporary files, 
you will have to disable the automatic clean up of the resources by passing the parameter
`{:manual-cleanup? true}`.
Instead of returning a manifold stream, it will return a vector composed
of a manifold stream and a callback to clean the associated resources.

```clojure
(defn multipart-handler [req]
  (let [[s cleanup-fn] (mp/decode-request req {:manual-cleanup? true})]
    (doseq [part (s/stream->seq s)]
      (if (:memory? part))
         (:content part)         ;; do what you want with the content
         (io/copy (:file part))) ;; copy the file on another location
    (cleanup-fn)))
```
