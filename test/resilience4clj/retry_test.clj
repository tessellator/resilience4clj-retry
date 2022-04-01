(ns resilience4clj.retry-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [resilience4clj.retry :as retry :refer [with-retry]])
  (:import [io.github.resilience4j.core ConfigurationNotFoundException]
           [io.github.resilience4j.retry MaxRetriesExceededException]
           [java.time ZonedDateTime]))

(defn- take-with-timeout!! [ch]
  (let [timeout-chan (async/timeout 5)]
    (async/alt!!
      ch ([v] v)
      timeout-chan :timeout)))

;; -----------------------------------------------------------------------------
;; Configuration

(deftest test-build-config
  (let [build-config #'retry/build-config
        config-map {:max-attempts 1
                    :fail-after-max-attempts true}
        config (build-config config-map)]
    (is (= 1 (.getMaxAttempts config)))
    (is (true? (.isFailAfterMaxAttempts config)))))

(deftest test-build-config--retry-exceptions
  (let [r (retry/retry :some-name {:retry-exceptions [NullPointerException]})]
    (try
      (with-retry r
        (throw (NullPointerException.)))
      (catch Throwable _))
    (is (= 1 (.. r getMetrics getNumberOfFailedCallsWithRetryAttempt)))

    (try
      (with-retry r
        (throw (ArrayIndexOutOfBoundsException.)))
      (catch Throwable _))
    (is (= 1 (.. r getMetrics getNumberOfFailedCallsWithoutRetryAttempt)))))

(deftest test-build-config--ignore-exceptions
  (let [r (retry/retry :some-name {:ignore-exceptions [ArrayIndexOutOfBoundsException]})]
    (try
      (with-retry r
        (throw (NullPointerException.)))
      (catch Throwable _))
    (is (= 1 (.. r getMetrics getNumberOfFailedCallsWithRetryAttempt)))

    (try
      (with-retry r
        (throw (ArrayIndexOutOfBoundsException.)))
      (catch Throwable _))
    (is (= 1 (.. r getMetrics getNumberOfFailedCallsWithoutRetryAttempt)))))

(deftest test-build-config--retry-on-result-predicate
  (let [r (retry/retry :some-name {:retry-on-result-predicate #(= 1.2 %)})
        f (constantly 1.2)
        result (with-retry r (f))]
    (is (= 1.2 result))
    (is (= 1 (.. r getMetrics getNumberOfFailedCallsWithRetryAttempt)))))

(deftest test-build-config--retry-exception-predicate
  (let [c (atom 2)
        f #(if (pos? (swap! c dec)) (throw (NullPointerException.)) 1.2)
        p #(instance? NullPointerException %)
        r (retry/retry :some-name {:retry-exception-predicate p})
        result (with-retry r (f))]
    (is (= 1.2 result))
    (is (= 1 (.. r getMetrics getNumberOfSuccessfulCallsWithRetryAttempt)))))

(deftest test-build-config--fail-after-max-attempts
  (let [r (retry/retry :some-name {:retry-on-result-predicate #(= 1.2 %)
                                   :fail-after-max-attempts true})
        f (constantly 1.2)]
    (is (thrown? MaxRetriesExceededException (with-retry r (f))))
    (is (= 2 (.. r getMetrics getNumberOfFailedCallsWithRetryAttempt)))))

;; -----------------------------------------------------------------------------
;; Registry

(deftest test-registry--given-config-maps
  (let [reg (retry/registry {:some {:max-attempts 5}
                             "other" {:max-attempts 10}})]
    (testing "the default configuration"
      (let [cfg (.getConfiguration reg "default")]
        (is (true? (.isPresent cfg)))
        (is (= 3 (.. cfg get getMaxAttempts)))))

    (testing "the added configurations"
      (testing "the 'some' configuration"
        (let [some-cfg (.getConfiguration reg "some")]
          (is (true? (.isPresent some-cfg)))
          (is (= 5 (.. some-cfg get getMaxAttempts)))))

      (testing "the 'other' configuration"
        (let [other-cfg (.getConfiguration reg "other")]
          (is (true? (.isPresent other-cfg)))
          (is (= 10 (.. other-cfg get getMaxAttempts))))))))

(deftest test-registry--override-default
  (let [reg (retry/registry {:default {:max-attempts 5}})
        cfg (.getDefaultConfig reg)]
    (is (= 5 (.getMaxAttempts cfg)))))

(deftest test-all-retries
  (let [reg (retry/registry)
        r1 (atom nil)
        r2 (atom nil)]
    (is (empty? (retry/all-retries reg)))

    (reset! r1 (retry/retry! reg :some-retry))
    (is (= #{@r1} (retry/all-retries reg)))

    (reset! r2 (retry/retry! reg :other-retry))
    (is (= #{@r1 @r2} (retry/all-retries reg)))))

(deftest test-all-retries--no-registry-provided
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (with-redefs [retry/default-registry reg]
      (is (= #{r} (retry/all-retries))))))

(deftest add-configuration!
  (let [reg (retry/registry)]
    (retry/add-configuration! reg :my-config {:max-attempts 6})

    (let [cfg-opt (.getConfiguration reg "my-config")]
      (is (true? (.isPresent cfg-opt)))
      (is (= 6 (.. cfg-opt get getMaxAttempts))))))

(deftest add-configuration!--no-registry-provided
  (let [reg (retry/registry)]
    (with-redefs [retry/default-registry reg]
      (retry/add-configuration! :my-config {:max-attempts 6})

      (let [cfg-opt (.getConfiguration reg "my-config")]
        (is (true? (.isPresent cfg-opt)))
        (is (= 6 (.. cfg-opt get getMaxAttempts)))))))

(deftest test-find
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (is (= r (retry/find reg :some-name)))))

(deftest test-find--no-matching-name
  (let [reg (retry/registry)]
    (is (nil? (retry/find reg :some-name)))))

(deftest test-find--no-registry-provided
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (with-redefs [retry/default-registry reg]
      (is (= r (retry/find :some-name))))))

(deftest test-remove!
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (is (= #{r} (retry/all-retries reg)) "before removal")

    (let [result (retry/remove! reg :some-name)]
      (is (= r result))
      (is (empty? (retry/all-retries reg))))))

(deftest test-remove!--no-registry-provided
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (with-redefs [retry/default-registry reg]
      (is (= #{r} (retry/all-retries reg)) "before removal")

      (let [removed (retry/remove! :some-name)]
        (is (= r removed))
        (is (empty? (retry/all-retries reg)))))))

(deftest test-remove!--no-matching-name
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})]
    (is (= #{r} (retry/all-retries reg)) "before removal")

    (let [result (retry/remove! reg :other-name)]
      (is (nil? result))
      (is (= #{r} (retry/all-retries reg))))))

(deftest test-replace!
  (let [reg (retry/registry)
        r (retry/retry! reg :some-name {})
        new (retry/retry! reg :some-name {})
        result (retry/replace! reg :some-name new)]
    (is (= r result))
    (is (= #{new} (retry/all-retries reg)))))

(deftest test-replace!--no-matching-name
  (let [reg (retry/registry)
        r (retry/retry :some-name {})
        result (retry/replace! reg :some-name r)]
    (is (nil? result))
    (is (empty? (retry/all-retries reg)))))

(deftest test-replace!--mismatched-name
  ;; This is an interesting case because normally the registry will
  ;; have retries with names that match the name in the registry itself. But 
  ;; using replace! you can change that.
  ;;
  ;; This test demonstrates that the end result of a replace! can
  ;; be a little unexpected...
  (let [reg (retry/registry)
        orig (retry/retry! reg :some-name {})
        new (retry/retry :other-name {})
        result (retry/replace! reg :some-name new)]
    (is (= result orig))
    (is (= #{new} (retry/all-retries reg)))

    (is (= "other-name" (retry/name (retry/find reg :some-name))))
    (is (nil? (retry/find reg :other-name)))))

(deftest test-replace!--no-registry-provided
  (let [reg (retry/registry)]
    (with-redefs [retry/default-registry reg]
      (let [old (retry/retry! reg :some-name {})
            new (retry/retry :some-name {})
            replaced (retry/replace! :some-name new)]
        (is (= old replaced))
        (is (= #{new} (retry/all-retries reg)))))))

;; -----------------------------------------------------------------------------
;; Registry Events

(deftest test-emit-registry-events!
  (let [reg (retry/registry)
        event-chan (async/chan 1)
        first-retry (atom nil)
        second-retry (retry/retry :some-retry {})]
    (retry/emit-registry-events! reg event-chan)

    (testing "when a retry is added to the registry"
      (reset! first-retry (retry/retry! reg :some-retry))
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :added
                :added-entry @first-retry}
               (dissoc event :creation-time)))))

    (testing "when a retry is replaced in the registry"
      (retry/replace! reg :some-retry second-retry)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :replaced
                :old-entry @first-retry
                :new-entry second-retry}
               event))))

    (testing "when a retry is removed from the registry"
      (retry/remove! reg :some-retry)
      (let [event (take-with-timeout!! event-chan)]
        (is (= {:event-type :removed
                :removed-entry second-retry}
               event))))))

(deftest test-emit-registry-events!--no-registry-provided
  (let [reg (retry/registry)
        event-chan (async/chan 1)]
    (with-redefs [retry/default-registry reg]
      (retry/emit-registry-events! event-chan)
      (retry/retry! reg :some-name))

    (let [event (take-with-timeout!! event-chan)]
      (is (= :added (:event-type event))))))

(deftest test-emit-registry-events!--with-only-filter
  (let [reg (retry/registry)
        event-chan (async/chan 1)]
    (retry/emit-registry-events! reg event-chan :only [:added])

    (testing "it raises the added event"
      (retry/retry! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (retry/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

(deftest test-emit-registry-events!--with-exclude-filter
  (let [reg (retry/registry)
        event-chan (async/chan 1)]
    (retry/emit-registry-events! reg event-chan :exclude [:added])

    (testing "it does not raise the added event"
      (retry/retry! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))

    (testing "it raises the removed event"
      (retry/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :removed (:event-type event)))))))

(deftest test-emit-registry-events!-only-filter-trumps-exclude-filter
  (let [reg (retry/registry)
        event-chan (async/chan 1)]
    (retry/emit-registry-events! reg event-chan :only [:added] :exclude [:added])

    (testing "it raises the added event"
      (retry/retry! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :added (:event-type event)))))

    (testing "it does not raise the removed event"
      (retry/remove! reg :some-name)
      (let [event (take-with-timeout!! event-chan)]
        (is (= :timeout event))))))

;; -----------------------------------------------------------------------------
;; Creation and fetching from registry

(deftest test-retry!
  (testing "given only a name, it looks up the retry in the default-registry"
    (let [reg (retry/registry)]
      (with-redefs [retry/default-registry reg]
        (let [r (retry/retry! :some-name)
              r2 (retry/retry! :some-name)]
          (is (= r r2))
          (is (= #{r} (retry/all-retries reg)))))))

  (testing "given a registry and a name"
    (let [reg (retry/registry)
          default-reg (retry/registry)]
      (with-redefs [retry/default-registry default-reg]
        (let [r (retry/retry! reg :some-name)
              r2 (retry/retry! reg :some-name)]
          (is (= r r2))
          (is (= #{r} (retry/all-retries reg)))
          (is (empty? (retry/all-retries default-reg))))))))

(deftest test-retry!-with-matching-config-name
  (let [reg (retry/registry {"myConfig" {:max-attempts 6}})
        default-reg (retry/registry)
        r (retry/retry! reg :some-retry "myConfig")]
    (is (= (.get (.getConfiguration reg "myConfig"))
           (.getRetryConfig r)))
    (is (= #{r} (retry/all-retries reg)))
    (is (empty? (retry/all-retries default-reg)))))

(deftest test-retry!-with-nonmatching-config-name
  (let [reg (retry/registry {})]
    (is (thrown? ConfigurationNotFoundException
                 (retry/retry! reg :some-retry "myConfig")))))

(deftest test-retry!-with-config-map
  (let [reg (retry/registry {})
        r (retry/retry! reg :some-name {:max-attempts 6})]
    (is (= #{r} (retry/all-retries reg)))
    (is (= 6 (.. r getRetryConfig getMaxAttempts)))))

;; -----------------------------------------------------------------------------
;; Execution

(deftest test-execute
  (let [r (retry/retry :my-retry {})
        c (atom 2)
        f #(if (pos? (swap! c dec)) (throw (NullPointerException.)) 5)
        result (retry/execute r f)]
    (is (= 5 result))))

(deftest test-with-retry
  (testing "it looks up the retry when a name is provided"
    (let [reg (retry/registry)
          captured (atom nil)]
      (with-redefs [retry/default-registry reg
                    retry/execute (fn [r & _] (reset! captured r))]
        (with-retry :new-breaker true))
      (is (= #{@captured} (retry/all-retries reg))))

    (testing "it uses the retry when it is provided"
      (let [reg (retry/registry)
            r (retry/retry :some-name {})
            captured (atom nil)]
        (with-redefs [retry/default-registry reg
                      retry/execute (fn [r & _] (reset! captured r))]
          (with-retry r true))

        (is (empty? (retry/all-retries reg)))
        (is (= r @captured))))))

;; -----------------------------------------------------------------------------
;; Retry properties

(deftest test-name
  (let [r (retry/retry :some-name {})]
    (is (= "some-name" (retry/name r)))))

;; -----------------------------------------------------------------------------
;; Retry events

(defn- check-base-event [event expected-event-type expected-retry-name]
  (let [{:keys [event-type retry-name creation-time]} event]
    (is (not= :timeout event))
    (is (= expected-event-type event-type))
    (is (= expected-retry-name retry-name))
    (is (instance? ZonedDateTime creation-time))))

(deftest test-emit-events!--on-success
  ;; NOTE: The success event is only raised if a retry is required. If the first
  ;; pass is successful, no event is raised. This suprised me!
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name {})
        c (atom 2)
        f #(if (pos? (swap! c dec)) (throw (NullPointerException.)) 0)]
    (retry/emit-events! r event-chan :only [:success])

    (with-retry r
      (f))

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :success "some-name"))))

(deftest test-emit-events!--on-error
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name {})
        ex (ex-info "some message" {})]
    (retry/emit-events! r event-chan :only [:error])

    (try
      (with-retry r
        (throw ex))
      (catch Throwable _))

    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :error "some-name"))))

(deftest test-emit-events!--on-ignored-error
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name {:retry-exception-predicate (constantly false)})
        ex (ex-info "some message" {:some :value})]
    (retry/emit-events! r event-chan)

    (try
      (with-retry r
        (throw ex))
      (catch Throwable _))

    (let [event (async/<!! event-chan)
          {:keys [last-throwable]} event]
      (check-base-event event :ignored-error "some-name")
      (is (= ex last-throwable)))))

(deftest test-emit-events!--on-retry
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name {})]
    (retry/emit-events! r event-chan :only [:retry])

    (try
      (with-retry r
        (throw (NullPointerException.)))
      (catch Throwable _))

    (let [event (async/<!! event-chan)
          {:keys [wait-interval]} event]
      (check-base-event event :retry "some-name")
      (is (pos? (.getNano wait-interval))))))

(deftest test-emit-events!-with-exclude-filter
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name)
        c (atom 2)
        f #(if (pos? (swap! c dec)) (throw (Exception.)) 1.2)]
    (retry/emit-events! r event-chan :exclude [:retry])

    (with-retry r
      (f))

    ;; normally, we would expect a couple of retry events, and then the success
    ;; event. However, since we are excluding retry, we should only see the
    ;; success event.
    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :success "some-name"))))

(deftest test-emit-events!-only-filter-trumps-exclude-filter
  (let [event-chan (async/chan 1)
        r (retry/retry :some-name {:interval-function (retry/interval 10)})
        c (atom 2)
        f #(if (pos? (swap! c dec)) (throw (Exception.)) 1.2)]
    (retry/emit-events! r event-chan :only [:success] :exclude [:success])

    (with-retry r
      (f))

    ;; normally, we would expect a couple of retry events, and then the success
    ;; event. However, since we are including only success, we only expect the
    ;; success event.
    (let [event (take-with-timeout!! event-chan)]
      (check-base-event event :success "some-name"))))