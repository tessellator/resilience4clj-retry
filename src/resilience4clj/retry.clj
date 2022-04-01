(ns resilience4clj.retry
  "Functions to create and execute retrys."
  (:refer-clojure :exclude [find name reset!])
  (:require [clojure.core.async :as async]
            [clojure.string :as str])
  (:import [io.github.resilience4j.core
            EventConsumer
            IntervalBiFunction
            IntervalFunction
            Registry$EventPublisher]
           [io.github.resilience4j.core.registry
            EntryAddedEvent
            EntryRemovedEvent
            EntryReplacedEvent]
           [io.github.resilience4j.retry
            Retry
            Retry$EventPublisher
            RetryConfig
            RetryRegistry]
           [io.github.resilience4j.retry.event
            AbstractRetryEvent
            RetryOnRetryEvent]
           [java.time Duration]
           [java.util Map Optional]
           [java.util.function Predicate]))

(set! *warn-on-reflection* true)

(defn- optional-value [^Optional optional]
  (when (.isPresent optional)
    (.get optional)))

(defn- name? [val]
  (or (string? val)
      (keyword? val)))

(defn- keywordize-enum-value [^Object enum-value]
  (-> (.toString enum-value)
      (str/lower-case)
      (str/replace #"_" "-")
      (keyword)))

;; -----------------------------------------------------------------------------
;; configuration

(defn- build-config [config]
  (let [{:keys [max-attempts
                wait-duration
                interval-function
                interval-bi-function
                retry-on-result-predicate
                retry-exception-predicate
                retry-exceptions
                ignore-exceptions
                fail-after-max-attempts]} config]
    (cond-> (RetryConfig/custom)

      max-attempts
      (.maxAttempts max-attempts)

      wait-duration
      (.waitDuration (Duration/ofMillis wait-duration))

      interval-function
      (as-> config
            (if (instance? IntervalFunction interval-function)
              (.intervalFunction config interval-function)
              (.intervalFunction config (reify IntervalFunction (apply [_ num-attempts] (interval-function num-attempts))))))

      interval-bi-function
      (as-> config
            (if (instance? IntervalBiFunction interval-bi-function)
              (.intervalBiFunction config interval-bi-function)
              (.intervalBiFunction config (reify IntervalBiFunction (apply [_ num-attempts throwable-or-result] (interval-bi-function num-attempts throwable-or-result))))))

      retry-on-result-predicate
      (.retryOnResult (reify Predicate (test [_ result] (retry-on-result-predicate result))))

      retry-exception-predicate
      (.retryOnException (reify Predicate (test [_ ex] (retry-exception-predicate ex))))

      retry-exceptions
      (.retryExceptions (into-array java.lang.Class retry-exceptions))

      ignore-exceptions
      (.ignoreExceptions (into-array java.lang.Class ignore-exceptions))

      fail-after-max-attempts
      (.failAfterMaxAttempts fail-after-max-attempts)

      :always
      (.build))))

;; -----------------------------------------------------------------------------
;; interval-function builders

(defn interval
  "Creates an interval function."
  [^long interval-millis]
  (IntervalFunction/of interval-millis))

(defn randomized
  "Creates a randomized interval function."
  ([^long interval-millis]
   (IntervalFunction/ofRandomized interval-millis))
  ([^long interval-millis ^double randomization-factor]
   (IntervalFunction/ofRandomized interval-millis randomization-factor)))

(defn exponential-backoff
  "Creates an exponential backoff interval function."
  ([^long initial-interval-millis]
   (IntervalFunction/ofExponentialBackoff initial-interval-millis))
  ([^long initial-interval-millis ^double multiplier]
   (IntervalFunction/ofExponentialBackoff initial-interval-millis multiplier)))

(defn exponential-random-backoff
  "Creates an exponential random backoff interval function."
  ([^long initial-interval-millis]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis))
  ([^long initial-interval-millis ^double multiplier]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis multiplier))
  ([^long initial-interval-millis ^double multiplier ^double randomization-factor]
   (IntervalFunction/ofExponentialRandomBackoff initial-interval-millis multiplier randomization-factor)))

;; -----------------------------------------------------------------------------
;; registry

(def default-registry
  "The global retry and config registry."
  (RetryRegistry/ofDefaults))

(defn- build-configs-map [configs-map]
  (into {} (map (fn [[k v]] [(clojure.core/name k) (build-config v)]) configs-map)))

(defn registry
  "Creates a registry with default values or a map of nam/config-map pairs."
  ([]
   (RetryRegistry/ofDefaults))
  ([configs-map]
   (let [^Map configs (build-configs-map configs-map)]
     (RetryRegistry/of configs))))

(defn all-retries
  "Gets all the retries in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([]
   (all-retries default-registry))
  ([^RetryRegistry registry]
   (set (.getAllRetries registry))))

(defn add-configuration!
  "Adds `config` to the `registry` under the `name`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name config]
   (add-configuration! default-registry name config))
  ([^RetryRegistry registry name config]
   (.addConfiguration registry (clojure.core/name name) (build-config config))))

(defn find
  "Finds the retry identified by `name` in `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (find default-registry name))
  ([^RetryRegistry registry name]
   (optional-value (.find registry (clojure.core/name name)))))

(defn remove!
  "Removes the retry identified by `name` from `registry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (remove! default-registry name))
  ([^RetryRegistry registry name]
   (optional-value (.remove registry (clojure.core/name name)))))

(defn replace!
  "Replaces the retry identified by `name` in `registry` with the specified `retry`.

  Uses [[default-registry]] if `registry` is not provided."
  ([name ^Retry retry]
   (replace! default-registry name retry))
  ([^RetryRegistry registry name ^Retry retry]
   (optional-value (.replace registry (clojure.core/name name) retry))))

;; -----------------------------------------------------------------------------
;; registry events

(defn- entry-added-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryAddedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :added-entry ^Retry (.getAddedEntry e)})))))

(defn- entry-removed-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryRemovedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :removed-entry ^Retry (.getRemovedEntry e)})))))

(defn- entry-replaced-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^EntryReplacedEvent e event]
        (async/offer! out-chan
                      {:event-type (keywordize-enum-value (.getEventType e))
                       :old-entry ^Retry (.getOldEntry e)
                       :new-entry ^Retry (.getNewEntry e)})))))

(def registry-event-types
  "The event types that can be raised by a registry."
  #{:added
    :removed
    :replaced})

(defn emit-registry-events!
  "Offers registry events to `out-chan`.

  The event types are identified by [[registry-event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively.

  Uses [[default-registry]] if `registry` is not provided."
  ([out-chan]
   (emit-registry-events! default-registry out-chan))
  ([^RetryRegistry registry out-chan & {:keys [only exclude]
                                        :or {exclude []}}]
   (let [events-to-publish (if only (set only)
                               (apply disj registry-event-types exclude))
         ^Registry$EventPublisher pub (.getEventPublisher registry)]
     (when (contains? events-to-publish :added)
       (.onEntryAdded pub (entry-added-consumer out-chan)))
     (when (contains? events-to-publish :removed)
       (.onEntryRemoved pub (entry-removed-consumer out-chan)))
     (when (contains? events-to-publish :replaced)
       (.onEntryReplaced pub (entry-replaced-consumer out-chan))))
   out-chan))

;; -----------------------------------------------------------------------------
;; creation and lookup

(defn retry!
  "Creates or fetches a retry with the specified name and config and stores it
  in `registry`.

  The config value can be either a config map or the name of a config map stored
  in the registry. If the retry already exists in the registry, the config value
  is ignored. 

  Uses [[default-registry]] if `registry` is not provided."
  ([name]
   (retry! default-registry name))
  ([^RetryRegistry registry name]
   (.retry registry (clojure.core/name name)))
  ([^RetryRegistry registry name config]
   (if (name? config)
     (.retry registry (clojure.core/name name) (clojure.core/name config))
     (let [^RetryConfig cfg (build-config config)]
       (.retry registry (clojure.core/name name) cfg)))))

(defn retry
  "Creates a retry with the `name` and `config`."
  ([name]
   (retry name {}))
  ([name config]
   (let [^RetryConfig cfg (build-config config)]
     (Retry/of (clojure.core/name name) cfg))))

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
  `(let [r# (if (instance? Retry ~retry)
              ~retry
              (retry! (clojure.core/name ~retry)))]
     (execute r# (fn [] ~@body))))

;; -----------------------------------------------------------------------------
;; retry properties

(defn name
  "Gets the name of `retry`."
  ([^Retry retry]
   (.getName retry)))

;; -----------------------------------------------------------------------------
;; retry events

(def event-types
  #{:retry
    :success
    :error
    :ignored-error})

(defn- base-event [^AbstractRetryEvent event]
  {:event-type (keywordize-enum-value (.getEventType event))
   :retry-name (.getName event)
   :creation-time (.getCreationTime event)
   :number-of-retry-attempts (.getNumberOfRetryAttempts event)
   :last-throwable (.getLastThrowable event)})

(defn- retry-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^RetryOnRetryEvent e event]
        (async/offer! out-chan
                      (assoc (base-event e)
                             :wait-interval (.getWaitInterval e)))))))

;; Note: success, error, and ignored-error do not add any additional fields, so
;; we just use a base consumer for them.
(defn- base-consumer [out-chan]
  (reify EventConsumer
    (consumeEvent [_ event]
      (let [^AbstractRetryEvent e event]
        (async/offer! out-chan
                      (base-event e))))))

(defn emit-events!
  "Offers events on `retry` to `out-chan`.

  The event types are identified by [[event-types]].

  This function also accepts `:only` and `:exclude` keyword params that are
  sequences of the event types that should be included or excluded,
  respectively."
  [^Retry retry out-chan & {:keys [only exclude]
                            :or {exclude []}}]
  (let [events-to-publish (if only
                            (set only)
                            (apply disj event-types exclude))
        ^Retry$EventPublisher pub (.getEventPublisher retry)]
    (when (contains? events-to-publish :retry)
      (.onRetry pub (retry-consumer out-chan)))
    (when (contains? events-to-publish :success)
      (.onSuccess pub (base-consumer out-chan)))
    (when (contains? events-to-publish :error)
      (.onError pub (base-consumer out-chan)))
    (when (contains? events-to-publish :ignored-error)
      (.onIgnoredError pub (base-consumer out-chan)))))