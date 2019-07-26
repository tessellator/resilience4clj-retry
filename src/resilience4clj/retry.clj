(ns resilience4clj.retry
  "Functions to create and execute retrys."
  (:require [clojure.spec.alpha :as s])
  (:import [io.github.resilience4j.retry
            IntervalFunction
            Retry
            RetryConfig
            RetryRegistry]
           [java.time Duration]
           [java.util.function
            Function
            Predicate]))

;; -----------------------------------------------------------------------------
;; configuration

(s/def ::max-attempts nat-int?)
(s/def ::wait-duration nat-int?)
(s/def ::interval-function (s/or :interval-function #(instance? IntervalFunction %)
                                 :fn fn?))
(s/def ::retry-on-result-predicate fn?)
(s/def ::retry-on-exception-predicate fn?)
(s/def ::retry-exceptions (s/coll-of class?))
(s/def ::ignore-exceptions (s/coll-of class?))

(s/def ::config
  (s/keys :opt-un [::max-attempts
                   ::wait-duration
                   ::interval-function
                   ::retry-on-result-predicate
                   ::retry-on-exception-predicate
                   ::retry-exceptions
                   ::ignore-exceptions]))

(s/def ::name
  (s/or :string (s/and string? not-empty)
        :keyword keyword?))

(defn- build-config [config]
  (let [{:keys [max-attempts
                wait-duration
                interval-function
                retry-on-result-predicate
                retry-on-exception-predicate
                retry-exceptions
                ignore-exceptions]} config]
    (cond-> (RetryConfig/custom)

      max-attempts
      (.maxAttempts max-attempts)

      wait-duration
      (.waitDuration (Duration/ofMillis wait-duration))

      interval-function
      (as-> rc
          (let [[t f] (s/conform ::interval-function interval-function)]
            (condp = t
              :interval-function
              (.intervalFunction rc f)

              :fn
              (.intervalFunction rc (reify Function (apply [_ num-attempts] (f num-attempts)))))))

      retry-on-result-predicate
      (.retryOnResult (reify Predicate (test [_ result] (retry-on-result-predicate result))))

      retry-on-exception-predicate
      (.retryOnException (reify Predicate (test [_ ex] (retry-on-exception-predicate ex))))

      retry-exceptions
      (.retryExceptions (into-array java.lang.Class retry-exceptions))

      ignore-exceptions
      (.ignoreExceptions (into-array java.lang.Class ignore-exceptions))

      :always
      (.build))))

;; -----------------------------------------------------------------------------
;; interval-function builders

(defn interval
  "Creates an interval function."
  [interval-millis]
  (IntervalFunction/of interval-millis))

(defn randomized
  "Creates a randomized interval function."
  ([interval-millis]
   (IntervalFunction/ofRandomized interval-millis))
  ([interval-millis randomization-factor]
   (IntervalFunction/ofRandomized interval-millis randomization-factor)))

(defn exponential-backoff
  "Creates an exponential backoff interval function."
  ([initial-interval-millis]
   (IntervalFunction/ofExponentialBackoff initial-interval-millis))
  ([initial-interval-millis multiplier]
   (IntervalFunction/ofExponentialBackoff initial-interval-millis multiplier)))

(defn exponential-random-backoff
  "Creates an exponential random backoff interval function."
  ([initial-interval-millis]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis))
  ([initial-interval-millis multiplier]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis multiplier))
  ([initial-interval-millis multiplier randomization-factor]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis multiplier randomization-factor)))

;; -----------------------------------------------------------------------------
;; registry

(def registry
  "The global retry and config registry."
  (RetryRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)]) configs-map)))

(defn configure-registry!
  "Overwrites the global registry with one that contains the configs-map.

  configs-map is a map whose keys are names and vals are configs. When a retry
  is created, you may specify one of the names in this map to use as the config
  for that retry.

  :default is a special name. It will be used as the config for retrys that do
  not specify a config to use."
  [configs-map]
  (alter-var-root (var registry)
                  (fn [_]
                    (RetryRegistry/of (build-configs-map configs-map)))))

;; -----------------------------------------------------------------------------
;; creation and lookup

(defn retry!
  "Creates or fetches a retry with the specified name and config and stores it
  in the global registry.

  The config value can be either a config map or the name of a config map stored
  in the global registry.

  If the retry already exists in the global registry, the config value is
  ignored."
  ([name]
   {:pre [(s/valid? ::name name)]}
   (.retry registry (clojure.core/name name)))
  ([name config]
   {:pre [(s/valid? ::name name)
          (s/valid? (s/or :name ::name :config ::config) config)]}
   (if (s/valid? ::name config)
     (.retry registry (clojure.core/name name) (clojure.core/name config))
     (.retry registry (clojure.core/name name) (build-config config)))))

(defn retry
  "Creates a retry with the specified name and config."
  [name config]
  (Retry/of (clojure.core/name name) (build-config config)))

;; -----------------------------------------------------------------------------
;; execution

(defn execute
  "Apply args to f within the retry context."
  [^Retry retry f & args]
  (.executeCallable retry #(apply f args)))

(defmacro with-retry
  "Executes body within the retry context.

  `retry` is either a retry or the name of one in the global registry. If you
  provide a name and a retry of that name does not already exist in the global
  registry, one will be created with the `:default` config."
  [retry & body]
  `(let [r# (if (s/valid? ::name ~retry)
              (retry! (clojure.core/name ~retry))
              ~retry)]
     (execute r# (fn [] ~@body))))
