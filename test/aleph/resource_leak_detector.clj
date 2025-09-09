(ns aleph.resource-leak-detector
  "Provides a Netty leak detector which is more reliable than the default implementation.

  Its chance of detecting leaks is around 95%. See `with-leak-collection` for details.

  Since it adds considerable runtime overhead, its main purpose is to be used in tests to assert
  intentional presence of leaks and to check for unintended leaks. The main API entry points for
  this are `with-leak-collection`, `with-expected-leaks` and `instrument-tests!`.

  To enable it, use the `:leak-detection` Leiningen profile.

  NOTE: Currently only improves reliability for detecting leaked ByteBufs. Other types of leaked
  resources will still be detected but not with the same reliability. Search Netty's codebase for
  invocations of `newResourceLeakDetector` to see which other candidate resources types there are."
  (:gen-class
   :extends io.netty.util.ResourceLeakDetector)
  (:require
   [clojure.test :as test]
   [clojure.tools.logging :as log])
  (:import
   (io.netty.buffer AbstractByteBufAllocator)
   (io.netty.util
    ResourceLeakDetector
    ResourceLeakDetector$Level
    ResourceLeakDetectorFactory)))

(defn enabled?
  "Checks whether the resource leak detector is enabled.

  See `aleph.resource-leak-detector` docstring on how to enable it."
  []
  (= "aleph.resource_leak_detector"
     (System/getProperty "io.netty.customResourceLeakDetector")))

(def active-resource-leak-detector-class
  (delay
    (class (.newResourceLeakDetector (ResourceLeakDetectorFactory/instance) String))))

(def active?
  (delay
    (= (Class/forName "aleph.resource_leak_detector")
       @active-resource-leak-detector-class)))

(defn ensure-consistent-config! []
  (when-not @active?
    (if (enabled?)
      (throw (RuntimeException.
              (str "`aleph.resource_leak_detector` is enabled but the active resource leak detector is "
                   "`" (.getName ^Class @active-resource-leak-detector-class)"`. This indicates that "
                   "`io.netty.util.ResourceLeakDetectorFactory` ran into an initialization error. "
                   "Enable Netty debug logging to diagnose the cause.")))
      (throw (RuntimeException.
              (str "Attempted to use `aleph.resource-leak-detector` API but it is not enabled. "
                   "To enable it, use the `:leak-detection` Leiningen profile.")))))
  (when-not (= ResourceLeakDetector$Level/PARANOID (ResourceLeakDetector/getLevel))
    (throw (RuntimeException.
            (str "`aleph.resource_leak_detector` requires `-Dio.netty.leakDetection.level=PARANOID`. "
                 "Current level is `" (ResourceLeakDetector/getLevel) "`.")))))

(def max-probe-gc-runs
  "Maximum number of times the GC will be run to detect a leaked probe."
  10)

(def probe-hint-marker
  "ALEPH LEAK DETECTOR PROBE")

(defn hint-record-pattern [hint-pattern]
  (re-pattern (str "(?m)^\\s*Hint: " hint-pattern "$")))

(def probe-hint-pattern
  (hint-record-pattern (str probe-hint-marker " \\d+")))

(defn probe? [leak]
  (re-find probe-hint-pattern (:records leak)))

(defn contains-hint? [hint leak]
  (re-find (hint-record-pattern hint) (:records leak)))

(defn remove-probes [leaks]
  (remove probe? leaks))

(let [cnt (atom 0)]
  (defn gen-probe-hint []
    (str probe-hint-marker " " (swap! cnt inc))))

(defn leak-probe! [hint]
  (-> AbstractByteBufAllocator/DEFAULT
      (.buffer 1)
      (.touch hint)))

;; NOTE: Not setting to bare `nil` to appease `clj-kondo`.
(def current-leaks (atom nil))

(defn force-leak-detection! []
  (System/gc)
  (System/runFinalization)
  ;; Transitively trigger a track() invocation which in turn works
  ;; off the leaked references queue.
  (-> AbstractByteBufAllocator/DEFAULT (.buffer 1) .release))

(defn await-probe! [probe-hint]
  (loop [n max-probe-gc-runs]
    (force-leak-detection!)
    (if (zero? n)
      (throw (RuntimeException. "Gave up awaiting leak probe. Try increasing `aleph.resource-leak-detector/max-probe-gc-runs`."))
      (when-not (some (partial contains-hint? probe-hint) @current-leaks)
        (recur (dec n))))))

(defn with-leak-collection
  "Invokes thunk `f` and tries hard to collect any resource leaks it may have caused.

  It works as follows: After invoking `f`, it intentionally leaks a (small) buffer, marked as a
  probe. It then runs the garbage collector and polls the leak detector in a loop until it reports a
  leak which matches the probe. Eventually, it invokes `handle-leaks` with a sequence of any other
  detected leaks it collected along the way (empty when none were detected).

  A leak is represented as a map with the following keys:
  - `:resource-type` is a string with the name of the leaked resource type (e.g. \"ByteBuf\")
  - `:records` is a multi-line string which holds the trace of the leak.

  Requires the leak detector to be `enabled?`.

  When nested, each child establishes a fresh leak collection scope. However, this only works within
  the same thread, so any asynchronous processes started by and outliving `f` will leak into the
  parent scope(s)."
  [f handle-leaks]
  (ensure-consistent-config!)
  (with-redefs [current-leaks (atom [])]
    (f)
    (let [hint (gen-probe-hint)]
      (leak-probe! hint)
      (await-probe! hint)
      (handle-leaks (remove-probes @current-leaks)))))

(defn log-leaks! [leaks]
  (doseq [{:keys [resource-type records]} leaks]
    ;; Log message cribbed from io.netty.util.ResourceLeakDetector's (protected) reportTracedLeak method
    (log/error (str "LEAK: " resource-type ".release() was not called before it's garbage-collected.")
               (str "See https://netty.io/wiki/reference-counted-objects.html for more information." records))))

(defn -needReport [_this]
  true)

(defn report-leak! [leak]
  (if @current-leaks
    (swap! current-leaks conj leak)
    (do
      (log/error "NOTE: The following leak occurred outside of a `with-leak-collection` scope.")
      (log-leaks! [leak]))))

(defn -reportTracedLeak [_this resource-type records]
  (report-leak! {:resource-type resource-type
                 :records records}))

;; NOTE: Since we require level PARANOID, this should never be called in practice.
(defn -reportUntracedLeak [_this resource-type]
  (report-leak! {:resource-type resource-type
                 :records "[untraced]"}))

(defmacro with-expected-leaks
  "Runs `body` and expects it to produce exactly `expected-leak-count` leaks. Intended for use in tests
  which intentionally leak resources.

  Requires the leak detector to be `enabled?`."
  [expected-leak-count & body]
  `(with-leak-collection
     (fn [] ~@body)
     ;; NOTE: Using a raw symbol here instead of a gensym to get nicer test failures.
     (fn [~'leaks]
       (when-not (test/is (= ~expected-leak-count (count ~'leaks)) "Unexpected leak count! See log output for details.")
         (log-leaks! ~'leaks)))))

(defn- report-test-leaks! [leaks]
  (when (seq leaks)
    (log-leaks! leaks)
    ;; We include the assertion here within the `when` form so that we don't add a mystery assertion
    ;; to every passing test (which is the common case).
    (test/is (zero? (count leaks))
             "Leak detected! See log output for details.")))

(defn- instrument-test-fn [tf]
  (if (::instrumented? tf)
    tf
    (with-meta
      (fn []
        (with-leak-collection tf report-test-leaks!))
      {::instrumented? true})))

(defn instrument-tests!
  "If `enabled?`, instruments all tests in the current namespace with leak detection by wrapping them
  in `with-leak-collection`. If leaks are detected, a corresponding (failing) assertion is injected
  into the test and the leak reports are logged at level `error`.

  Usually placed at the end of a test namespace.

  Note that this is intentionally not implemented as a fixture since there is no clean way to make a
  test fail from within a fixture: Neither a failing assertion nor throwing an exception will
  preserve which particular test caused it. See
  e.g. https://github.com/technomancy/leiningen/issues/2694 for an example of this."
  []
  (when (enabled?)
    (->> (ns-interns *ns*)
         vals
         (filter (comp :test meta))
         (run! (fn [tv]
                 (alter-meta! tv update :test instrument-test-fn))))))

(if (enabled?)
  (log/info "aleph.resource-leak-detector enabled.")
  (log/info "aleph.resource-leak-detector disabled. This means resource leaks will be reported less accurately."
            "To enable it, use the `:leak-detection` Leiningen profile."))
