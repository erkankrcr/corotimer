# corotimer

[![CI](https://github.com/erkankrcr/corotimer/actions/workflows/ci.yml/badge.svg)](https://github.com/erkankrcr/corotimer/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A Kotlin Multiplatform countdown timer and stopwatch, built on `StateFlow` and a monotonic,
sleep-inclusive clock. JVM and Android are supported today; iOS is declared but not yet shipped
(see [Platform support](#platform-support)).

```kotlin
val timer = viewModelScope.countdownTimer(startTime = 5.minutes, startFormat = "MM:SS") {
    changeFormatWhen(10.seconds, "SS.LL")
    actionWhen(3.seconds) { playTickSound() }
    onFinish { showTimeUpDialog() }
}

timer.start()
// timer.state: StateFlow<TimeState>
```

A runnable version of this, plus a stopwatch, lives in [`sample-jvm`](sample-jvm) —
`./gradlew :sample-jvm:run` prints both ticking live.

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenCentral() /* or mavenLocal() while trying this out */ }
}

// build.gradle.kts
dependencies {
    implementation("com.erkankrcr:corotimer:1.0.0") // JVM
    // implementation("com.erkankrcr:corotimer-android:1.0.0") is resolved automatically for
    // Android targets by the Kotlin Multiplatform plugin; you don't reference it directly.
}
```

## Countdown timer

```kotlin
val timer: CountdownTimer = scope.countdownTimer(
    startTime = 90.seconds,
    startFormat = "SS",
) {
    repeat(3)                                 // or repeatForever()
    onCycleComplete { cycle -> /* ... */ }     // fires after every cycle but the last
    onFinish { /* ... */ }                     // fires once, after the last cycle
}

timer.start()
timer.pause()
timer.resume()
timer.setTime(30.seconds)   // re-anchors in place, in any phase
timer.reset()               // back to the configured startTime
timer.release()             // stops the tick loop for good; state stops emitting
```

`timer.state` is a `StateFlow<TimeState>` — `Idle`, `Running`, `Paused` or `Finished` — each
carrying a ready-to-render `currentMillis` and `formattedTime`, so a UI never needs to special-case
"not started yet" or "just finished".

### Which scope to use

`countdownTimer`/`stopwatch` are extensions on `CoroutineScope`, not tied to `viewModelScope` — any
scope works, including one you create yourself:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val timer = scope.countdownTimer(startTime = 30.seconds, startFormat = "SS") {
    onFinish { println("done") }
}
```

Two things follow from that:

- [`release()`](corotimer/src/commonMain/kotlin/com/erkankrcr/corotimer/Corotimer.kt) only cancels
  the timer's own child job, never the scope it was built with — so several timers can share one
  scope, and releasing one never touches the others.
- The scope's lifecycle is yours to manage. The library doesn't own it, so if you created it
  yourself (rather than reusing `viewModelScope`/`lifecycleScope`), you're responsible for
  cancelling it — a `SupervisorJob` is the usual choice so one timer's error can't take the others
  down with it.

## Stopwatch

```kotlin
val watch: Stopwatch = scope.stopwatch(startFormat = "MM:SS") {
    changeFormatWhen(1.hours, "HH:MM:SS")
}

watch.start()
val split: Lap? = watch.lap()   // number, lapMillis (delta), splitMillis (total), both formatted
watch.laps                      // List<Lap>, oldest first
```

A stopwatch never emits `Finished` and has no `repeat` — those are countdown-only concepts, enforced
by the type system rather than documented as a no-op.

## Format patterns

| Symbol | Meaning |
|---|---|
| `H` | hours |
| `M` | minutes |
| `S` | seconds |
| `L` | sub-seconds — `L` tenths, `LL` hundredths, `LLL` milliseconds |
| `#` | escape — `#H` renders a literal `H`; `##` renders a literal `#` |

Repeat a symbol to set its width (`"MM:SS"` zero-pads both to two digits); only the *first* run of
each symbol is live, so `"HH:MM:HH"` renders hours, minutes, then the literal text `HH`. The
largest unit in the pattern absorbs everything above it (`"MM:SS"` at 90 minutes renders `"90:00"`,
not `"30:00"`), and overflow widens rather than truncates (`"HH"` at 100 hours renders `"100"`).

Use `TimeFormatter` directly to render a duration without a timer:

```kotlin
TimeFormatter.format("MM:SS.LL", 83_450)   // "01:23.45"
val formatter = TimeFormatter.parse("SS")
formatter.format(5_000)                    // "05"
```

## Testing your own code against a timer

`countdownTimer`/`stopwatch` are extensions on `CoroutineScope`, and take an injectable
[`MonotonicClock`](corotimer/src/commonMain/kotlin/com/erkankrcr/corotimer/MonotonicClock.kt) — so
in a test, pass a `kotlinx.coroutines.test.TestScope` as the receiver and a clock backed by its
`TestCoroutineScheduler`. `advanceTimeBy`/`runCurrent` then drive the real `start()` → `delay()` →
tick → emit path in virtual time, with no manual state stepping:

```kotlin
@Test
fun countsDown() = runTest {
    val clock = MonotonicClock { testScheduler.currentTime }
    val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)

    timer.start()
    advanceTimeBy(5_000)
    runCurrent()

    assertIs<TimeState.Finished>(timer.state.value)
}
```

## Design notes

- **State lives in one place.** Every timer is one immutable snapshot behind a single
  `MutableStateFlow`, mutated only through a pure reducer under a compare-and-set retry loop. Public
  methods (`start`, `pause`, ...) are plain, non-suspending functions — `pause(); state.value` never
  races a dispatcher.
- **The clock is sleep-inclusive.** `MonotonicClock` is injectable specifically because
  `kotlin.time.TimeSource.Monotonic` maps to `System.nanoTime()` on the JVM/Android side, which
  stops advancing in deep sleep. The Android actual uses `SystemClock.elapsedRealtime()`; the iOS
  actual uses `mach_continuous_time()` for the same reason.
- **Displayed time is always recomputed from the clock**, never accumulated from tick counts, so no
  `TickPolicy` and no slow `actionWhen`/`onFinish` callback can make a timer drift.

## Platform support

- ✅ `jvm`
- ✅ `androidTarget`
- 🚧 `iosX64`, `iosArm64`, `iosSimulatorArm64` — targets are declared and the clock implementation
  is written, but they haven't been through a device/simulator test pass yet. Contributions with
  Xcode/simulator access are welcome.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
