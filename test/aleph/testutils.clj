(ns aleph.testutils
  (:import (io.netty.util AsciiString)))

(defn str=
  "AsciiString-aware equals"
  [^CharSequence x ^CharSequence y]
  (AsciiString/contentEquals x y))

