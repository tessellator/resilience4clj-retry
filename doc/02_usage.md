## 2. Usage

### Executing Code Protected by Retry

There are two ways to execute code to be protected by a retry: `execute` and
`with-retry`.

`execute` executes a single function within the retry context and applies any
args to it.

```clojure
> (require '[resilience4clj.retry :as r])
;; => nil

> (defn always-throws []
    (println "Got here")
    (throw (ex-info "thrown" {})))
;; => #'user/always-throws

> (r/execute (r/retry! :my-retry) always-throws)
;; Got here
;; Got here
;; Got here
;; ExceptionInfo thrown ...

> (r/execute (r/retry! :my-retry) map inc [1 2 3])
;; => (2 3 4)
```

`execute` is rather low-level. To make execution more convenient, this library
also includes a `with-retry` macro that executes several forms within the context
of a retry. When you use the macro, you must either provide a retry or the name
of one in the global registry. If you provide a name and a retry of that name
does not already exist in the global registry, one is created with the `:default`
config.

In the following example, a retry named `:my-retry` will be used to attempt to
GET `https://www.example.com` up to the default three times.

```clojure
> (require '[resilience4clj.retry :refer [with-retry]])
;; => nil

> (with-retry :my-retry
    (http/get "https://www.example.com"))
```
