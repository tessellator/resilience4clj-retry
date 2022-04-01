# resilience4clj-retry

A small Clojure wrapper around the
[resilience4j Retry module](https://resilience4j.readme.io/docs/retry).

Requires Clojure 1.5 or later for JDK 8, and Clojure 1.10 or later for JDK 9+.

[![clojars badge](https://img.shields.io/clojars/v/tessellator/resilience4clj-retry.svg)](https://clojars.org/tessellator/resilience4clj-retry)
[![cljdoc badge](https://cljdoc.org/badge/tessellator/resilience4clj-retry)](https://cljdoc.org/d/tessellator/resilience4clj-retry/CURRENT)

## Quick Start

The following code defines a function `make-remote-call` that will retry in case
of a failure using a retry named `:some-name` and stored in the default registry.
If the retry does not already exist, one is created.

```clojure
(ns myproject.some-client
  (:require [clj-http.client :as http]
            [resilience4clj.retry :refer [with-retry]])

(defn make-remote-call []
  (with-retry :some-name
    (http/get "https://www.example.com")))
```

Refer to the [configuration guide](/doc/01_configuration.md) for more
information on how to configure the global registry as well as individual
retrys.

Refer to the [usage guide](/doc/02_usage.md) for more information on how to
use retrys.

## License

Copyright © 2019-2022 Thomas C. Taylor and contributors.

Distributed under the Eclipse Public License version 2.0.
