# sample-jvm

A runnable, plain-JVM consumer of `:corotimer` — it only uses public API, so compiling this module
is itself a check that the library's public surface is self-contained.

See [`Main.kt`](src/main/kotlin/com/erkankrcr/corotimer/sample/Main.kt) for the full source; it
starts a 3-second countdown, collects `timer.state` as it ticks, then runs a stopwatch and records
a lap.

## Run it

```sh
./gradlew :sample-jvm:run
```

Expected output:

```
--- countdown ---
countdown: 03 (Running)
countdown: 02 (Running)
1 second to go!
countdown: 01 (Running)
Countdown finished.
countdown: 00 (Finished)
--- stopwatch ---
lap: 00.25
final: 00.25
```

For the full API reference, see the [root README](../README.md).
