(ns aleph.testutils
  (:require manifold.test)
  (:import (io.netty.util AsciiString)
           (java.net InetSocketAddress)
           (java.nio.channels ServerSocketChannel)))

(defn str=
  "AsciiString-aware equals"
  [^CharSequence x ^CharSequence y]
  (AsciiString/contentEquals x y))

(defn bound-channel
  "Returns a new server-socket channel bound to a `port`."
  ^ServerSocketChannel [port]
  (doto (ServerSocketChannel/open)
    (.bind (InetSocketAddress. port))))

(defn instrument-tests-with-dropped-error-deferred-detection! []
  (when (= "true" (System/getProperty "aleph.testutils.detect-dropped-error-deferreds"))
    (manifold.test/instrument-tests-with-dropped-error-detection!)))
