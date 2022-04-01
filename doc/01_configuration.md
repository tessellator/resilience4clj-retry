## 1. Configuration

### Project Dependencies

resilience4clj-retry is distributed through [Clojars](https://clojars.org) with
the identifier `tessellator/resilience4clj-retry`. You can find the version
information for the latest release at
https://clojars.org/tessellator/resilience4clj-retry.

If you are using JDK 8, you may use any of version of Clojure 1.5+. However, if
you are using JDK 9 or later, you must use Clojure 1.10+ due
to [this bug](https://clojure.atlassian.net/browse/CLJ-2284).

### Configuration Options

The following table describes the options available when configuring retrys as
well as default values. A `config` is a map that contains any of the keys in the
table. Note that a `config` without a particular key will use the default value
(e.g., `{}` selects all default values).

| Configuration Option         | Default Value                | Description                                                                                                                        |
| ---------------------------- | ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `:max-attempts`              | 3                            | The maximum number of retry attempts                                                                                               |
| `:wait-duration`             | 500                          | The fixed number of milliseconds to wait between attempts                                                                          |
| `:interval-function`         | `(constantly wait-duration)` | A function that receives the current number of attempts after a failure and returns the next wait duration                         |
| `:interval-bi-function`      | `(constantly wait-duration)` | A function that receives the current number of attempts and the error or result after a failure and returns the next wait duration |
| `:retry-on-result-predicate` | `(constantly false)`         | A predicate that receives a result and determines whether it should be retried                                                     |
| `:retry-exception-predicate` | `(constantly true)`          | A predicate that receives an exception and determines whether the result needs to be retried                                       |
| `:retry-exceptions`          | []                           | A vector of exception types which should count as failures                                                                         |
| `:ignore-exceptions`         | []                           | A vector of exception types which should not count as failures                                                                     |
| `:fail-after-max-attempts`   | false                        | A boolean value indicating whether a MaxRetriesExceededException should be thrown after failing the maximum number of retries      |

There are a number of functions in this library that build interval functions
including `interval`, `randomized`, `exponential-backoff`, and
`exponential-random-backoff`. However, you may also create any function that
receives the current number of attempts and returns a new wait duration.

A `config` can be used to configure the global registry or a retry when it is
created.

### Registries

A registry is an entity that stores retries and configurations. When a retry is
created with the `retry!` function, it will be associated with a registry and
can be looked up by name in the registry afterward.

This library creates a `default-registry` that is used when a registry is not
provided but is required. The registry may contain `config` values as well as
retry instances. In the following example code, any of the instances of the
`reg` parameter may be dropped to use the default registry.

The function `retry!` will look up or create a retry in a registry. The function
accepts a name and optionally the name of a config or a config map.

```clojure
(ns myproject.core
  (:require [resilience4clj.retry :as r])

;; The following creates two configs: the default config and the AttemptMoreTimes
;; config. The default config uses only the defaults and will be used to create
;; retrys that do not specify a config to use.
;;
;; Note that the "default" configuration here is not necessary; a default config
;; with the default values is included in a registry when it is created. However,
;; you can provide a different configuration and assign it as the default config.
(r/configure-registry! {"default"    {}
                        "AttemptMoreTimes" {:max-attempts 5}})

;; You may also add configurations after a registry has been created. The
;; following code adds a new configuration to the registry created in the
;; previous lines.
(r/add-configuration! reg "AttemptMoreTimes" {:max-attempts 5})

;; create a retry named :name using the "default" config from the registry and
;; store the result in the registry
(r/retry! reg :name)

;; create a retry named :attempt-more using the "AttemptMoreTimes" config from
;; the registry and store the result in the registry
(r/retry! reg :attempt-more "AttemptMoreTimes")

;; create a retry named :custom-config using a custom config map and store the
;; result in the registry
(r/retry! reg :custom-config {:max-attempts 5
                              :wait-duration 1000})
```

### Custom Retrys

While convenient, it is not required to use the global registry. You may instead
choose to create retrys and manage them yourself.

In order to create a retry that is not made available globally, use the `retry`
function, which accepts a name and config map.

The following code creates a new retry with the default config options.

```clojure
(ns myproject.core
  (:require [resilience4clj.retry :as r]))

(def my-retry (r/retry :my-retry))
```
