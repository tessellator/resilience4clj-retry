# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 31 March 2022

### Added

- **[BREAKING]** a `registry` function. Breaking because `registry` was a var
  containing a registry in a previous version. That var is now `default-registry`.
- `interval-bi-function` and `fail-after-max-attempts` configuration options
- `emit-registry-events!` and `emit-events!` functions
- functions for interacting with registries: `add-configuration!`, `find`, `remove!`, and `replace!`
- Clojure 1.11 support
- Tests!

### Changed

- **[BREAKING]** renamed `registry` to `default-registry`
- `circuit-breaker!` can now accept a registry as a param
- Upgraded to resilience4j 1.7.1
- Relaxed required Clojure to 1.5.1 for JDK 8 and documented requirement of Clojure 1.10+ for JDK 9+
- Updated docs and docstrings to reflect new API changes.

### Removed

- **[BREAKING]** `configure-registry!` function
- **[BREAKING]** specs

## [0.3.0] - 20 February 2020

### Changed

- Updated to use resilience4j-retry 1.3.1

## [0.2.0] - 27 January 2020

### Changed

- Updated to use resilience4j-retry 1.2.0

## [0.1.0] - 26 July 2019

### Added

- Added initial retry wrapper implementation
