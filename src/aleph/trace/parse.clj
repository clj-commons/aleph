;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.trace.parse
  (:require
    [clojure.string :as str])
  (:import
    [java.util.regex Pattern]))

;;;

(def ^{:dynamic true} *input*)
(def ^{:dynamic true} *offset* 0)

(defn raise
  ([s]
     (raise 0 s))
  ([offset msg]
     (throw
       (Exception.
         (str "\n" *input* "\n"
           (apply str (repeat (+ *offset* offset) " ")) "^\n"
           msg)))))

;;;

(defn token
  ([x]
     (if (fn? x)
       x
       (token x identity)))
  ([x parser]
     (if (fn? x)
       (fn [s]
         (when-let [[v s] (x s)]
           [(parser v) s]))
       (let [s (if (string? x)
                x
                (.pattern ^Pattern x))
             p (Pattern/compile (str "^" s))]
         (fn [^String s]
           (when-let [match (first (re-seq p s))]
             [(parser match) (.substring s (count match))]))))))

(defmacro deftoken
  ([name & params]
     `(def ~name (token ~@params))))

;;;

(defn route [& key-vals]
  (let [pairs (partition 2 key-vals)
        patterns (map first pairs)
        tokens (map second pairs)
        matchers (map token patterns)
        lookup (zipmap matchers tokens)]
    (fn [s]
      (if-let [[m s] (some
                       (fn [m]
                         (when-let [[_ s] (m s)]
                           [m s]))
                       matchers)]
        ((lookup m) s)
        (raise (->> patterns (interpose ", ") (str "Expected one of ") (apply str)))))))

(defn ignore [p]
  (let [t (token p)]
    (fn [s]
      (if-let [[_ s] (t s)]
        [::ignore s]))))

(defn expect [p]
  (let [t (token p)]
    (fn [s]
      (if-let [[_ s] (t s)]
        [::ignore s]
        (raise (str "Expected " p))))))

;;;

(defn chain [& tokens]
  (fn [s]
    (let [len (count s)]
      (loop [tokens tokens, remaining s, acc []]
        (if (empty? tokens)
          [(remove #(= ::ignore %) acc) remaining]
          (when-let [[v remaining]
                     (binding [*offset* (+ *offset* (- len (count remaining)))]
                       ((first tokens) remaining))]
            (recur (rest tokens) remaining (conj acc v))))))))

(defn many [token]
  (fn [s]
    (let [len (count s)]
      (loop [remaining s, acc []]
        (if (empty? remaining)
          [acc remaining]
          (if-let [[v remaining]
                     (binding [*offset* (+ *offset* (- len (count remaining)))]
                       (token remaining))]
            (recur remaining (conj acc v))
            [acc remaining]))))))

(defn second* [a b]
  (let [t (chain (ignore a) b)]
    (fn [s]
      (when-let [[v s] (t s)]
        [(first v) s]))))

(defn maybe [t]
  (fn [s]
    (if-let [x (t s)]
      x
      [nil s])))

(defn one-of [& tokens]
  (fn [s]
    (some #(% s) tokens)))

(defn parser [t]
  (fn [s]
    (let [s (str/trim s)]
      (binding [*input* s]
        (if-let [[v remainder] (t s)]
          (if (empty? remainder)
            v
            (raise (- (count s) (count remainder)) "Unexpected characters."))
          (raise "Failed to parse."))))))

;;;

(declare stream)
(declare pair)

(deftoken pattern #"[a-zA-Z0-9:_\-\*]+")
(deftoken id #"[_a-zA-Z][a-zA-Z0-9\-_]*")
(deftoken comparison #"[<|>|=|~=]")
(deftoken field #"[_a-zA-Z][a-zA-Z0-9\-_\.]*")
(deftoken number #"[0-9\.]+" read-string)
(deftoken string #"[a-zA-Z0-9\-\*_]")
(deftoken whitespace #"[ \t,]*")
(deftoken empty-token #"")
(deftoken colon #"[ \t]*:[ \t]*")

(def relationship
  (chain
    (ignore whitespace)
    field
    (ignore whitespace)
    comparison
    (ignore whitespace)
    (one-of number string)))

(def tuple
  (token
    (chain
      (ignore whitespace)
      (ignore #"\[")
      (chain field (many (second* whitespace field)))
      (ignore whitespace)
      (expect #"\]"))
    (fn [[[a b]]]
      (vec (list* a b)))))

(let [t (delay (token (chain colon stream) second))]
  (defn substream [s]
    (@t s)))

(let [t (delay (one-of tuple pair substream relationship field number))]
  (defn param [s]
    (@t s)))

(def pair
  (token
    (chain
      id
      colon
      param)
    (fn [[k _ v]]
      {k v})))

(def params
  (token
    (chain
      (ignore whitespace)
      (maybe param)
      (many (second* whitespace param)))
    (fn [[a b]]
      (if a
        (list* a b)
        b))))

;;;

(defn collapse-options [s]
  (map
    (fn [idx v]
      (if (and (map? v) (= 1 (count v)))
        v
        {idx v}))
    (iterate inc 0)
    s))

(def operator
  (token
    (chain id
      (route
        #"\(" (token (chain params (ignore whitespace) (expect #"\)\.?")) first)
        #"\.|" (token empty-token (constantly ::none))))
    (fn [[name options]]
      (if (= options ::none)
        {:name "lookup"
         :options {:field name}}
        {:name name
         :options (apply merge {} (collapse-options options))}))))

;;;

(defn collapse-group-bys [s]
  (let [pre (take-while #(not= "group-by" (:name %)) s)]
    (if (= pre s)
      s
      (concat
        pre
        (let [s (drop (count pre) s)]
          [(assoc (first s)
             :operators (collapse-group-bys (rest s)))])))))

(def stream
  (token
    (chain pattern
      (route
        #"\." (many operator)
        #"" (token empty-token (constantly []))))
    (fn [[pattern operators]]
      {:pattern pattern
       :operators (collapse-group-bys operators)})))

;;;

(defn parse-stream [s]
  ((parser stream) s))



