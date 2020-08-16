(ns aleph.exceptions
  (:require [com.rpl.defexception :refer [defexception]]))

(defexception ConnectionTimeoutException)
(defexception PoolTimeoutException)
(defexception ProxyConnectionTimeoutException)
(defexception ReadTimeoutException)
(defexception RequestTimeoutException)
