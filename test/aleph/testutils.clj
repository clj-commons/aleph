(ns aleph.testutils
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
