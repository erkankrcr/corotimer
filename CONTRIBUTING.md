# Contributing

## Requirements

- JDK 17+
- Android SDK with `platform-34` and `build-tools` matching `compileSdk 34` (only needed to build
  the `androidTarget`; the JVM target and tests don't need it)

`local.properties` (gitignored) needs an `sdk.dir` pointing at your SDK install if Android Studio
hasn't already written one for you.

## Building

```sh
./gradlew build       # compiles all targets, runs jvmTest + Android unit tests, runs detekt
./gradlew jvmTest      # just the JVM test suite
./gradlew detekt       # static analysis only
```

iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) are declared but only compile on macOS
with Xcode installed. If you're on macOS, `./gradlew iosSimulatorArm64Test` runs the common test
suite against that target too.

## Code style

- `explicitApi()` is on — every public declaration needs an explicit visibility modifier and,
  where inferred types would otherwise leak, an explicit type.
- `allWarningsAsErrors` is on. A change that compiles with a warning fails the build.
- detekt runs with `buildUponDefaultConfig` plus `config/detekt/detekt.yml`; keep it clean rather
  than adding suppressions.
- Formatting follows `.editorconfig` (4-space indent, 140-column limit).

## Tests

New behavior needs a test. Prefer the pattern already used throughout `commonTest`: drive the
public API through `kotlinx.coroutines.test.runTest` with a `MonotonicClock` backed by
`TestCoroutineScheduler` (see `testutil/VirtualClock.kt`), rather than calling internal reducer or
scheduler functions directly — that way a test also exercises the real `start()` → `delay()` →
tick path, not just the state math.

## Pull requests

- Keep PRs focused on one change.
- Update `CHANGELOG.md` under `Unreleased` for anything user-visible.
- `./gradlew build` should pass before you open the PR — CI runs the same command.
