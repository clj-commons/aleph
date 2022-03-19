(ns aleph.clj-kondo-hooks)

(defmacro def-http-method [method]
  `(defn ~method
     ([url#])
     ([url# options#])))
