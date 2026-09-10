# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-09-10

### Added

- `countdownTimer { }` / `stopwatch { }` builder DSL on `CoroutineScope`.
- Single `TimeState` hierarchy (`Idle`, `Running`, `Paused`, `Finished`) shared by both timer
  kinds, exposed as a `StateFlow<TimeState>`.
- Pattern-based time formatting (`TimeFormatter`) with live format switching via
  `changeFormatWhen`.
- `actionWhen` threshold callbacks and `onCycleComplete` / `onFinish` for countdowns.
- Stopwatch lap recording (`lap()`, `laps`).
- Sleep-inclusive monotonic clock, with platform actuals for JVM, Android and iOS.
- JVM and Android publishing artifacts (Maven POM, sources jars, Android AAR).
