(ns aleph.testutils
  (:require
   [aleph.netty :as netty])
  (:import
   (io.netty.util AsciiString)
   (java.io Closeable)
   (java.net ServerSocket Socket)))

(defn str=
  "AsciiString-aware equals"
  [^CharSequence x ^CharSequence y]
  (AsciiString/contentEquals x y))

(defn passive-tcp-server
  "Starts a TCP server which never accepts a connection."
  [port]
  (let [;; A backlog of 0 would be ideal for this purpose but: "The value provided should be greater
        ;; than 0. If it is less than or equal to 0, then an implementation specific default will be
        ;; used." Source:
        ;; https://docs.oracle.com/en%2Fjava%2Fjavase%2F21%2Fdocs%2Fapi%2F%2F/java.base/java/net/ServerSocket.html#%3Cinit%3E(int,int)
        backlog 1
        server (ServerSocket. port backlog)
        port (.getLocalPort server)
        ;; Fill up the backlog with pending connection attempts. For some reason, the backlog length
        ;; is off by one, thus the `inc`.
        pending-connects (doall (repeatedly (inc backlog) #(Socket. "localhost" (int port))))]
    (reify
      netty/AlephServer
      (port [_]
        port)
      (wait-for-close [_]
        true)
      Closeable
      (close [_]
        (run! #(.close %) pending-connects)
        (.close server)))))
