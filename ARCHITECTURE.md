# Architecture

This document is the "why" behind corotimer's design: what each piece does, why it's shaped the
way it is, and which specific bug each design choice closes off. The public API (`README.md`)
tells you how to use the library; this tells you why it's built this way.

## Module layout

```
corotimer/          the library (published)
sample-jvm/          a plain-JVM consumer, proves the public API is self-contained
```

The library lives in a `:corotimer` subproject rather than the Gradle root project. That's what
lets `settings.gradle.kts` name the root `corotimer-root` while the published artifact, POM, and
Maven coordinates are all controlled from one place (`corotimer/build.gradle.kts`) without the
root project's identity leaking into them.

`sample-jvm` only imports public API — no `internal` access. Compiling it is itself a check that
`explicitApi()` never let an internal type leak into the public surface (see
[Build configuration](#build-configuration)).

## Public API

### Extension functions, not classes

```kotlin
public fun CoroutineScope.countdownTimer(...): CountdownTimer
public fun CoroutineScope.stopwatch(...): Stopwatch
```

Both entry points are extensions on `CoroutineScope`, not constructors. The receiver scope is
where the tick loop's coroutine is launched as a child, and it's what
[`release()`](#release-and-the-child-job) cancels a child of — never the scope itself. This means
any scope works: `viewModelScope`, `lifecycleScope`, a `TestScope` in a test, or one you build
yourself (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`). The library never owns or
cancels a scope it didn't create.

### One `TimeState` hierarchy for both timer kinds

```kotlin
public sealed interface TimeState {
    public val currentMillis: Long
    public val formattedTime: String
    public val cycle: Int
    data class Idle(...)     : TimeState
    data class Running(...)  : TimeState
    data class Paused(...)   : TimeState
    data class Finished(...) : TimeState   // currentMillis is always 0
}
```

A countdown and a stopwatch share this one hierarchy on purpose: a UI can `when` over `TimeState`
once and render either kind without an `as TimerState.Running` cast. A stopwatch simply never
produces `Finished`. Every variant — including `Idle`, before `start()` is ever called, and
`Finished` — carries a ready `formattedTime`, so a screen never special-cases "not started yet" or
"just finished" as a null or blank string.

### No `stop()`

```kotlin
public interface Corotimer {
    public val state: StateFlow<TimeState>
    public fun start()
    public fun pause()
    public fun resume()
    public fun reset()
    public fun setTime(millis: Long)
    public fun release()
}
```

`stop()` is deliberately absent. In the design this replaces, `stop()` meant "pause", and pausing
a countdown that had already reached `Finished` silently overwrote it with `Paused(0)` — the timer
forgot it had finished. Splitting the concept into `pause()` (freezes `Running`, no-op on anything
else, including `Finished`) and `reset()` (returns to the configured value, legal in any phase)
makes that bug structurally impossible: there's no single method that means both "freeze" and
"go back to nothing," so there's nothing to conflate.

Every method here is plain and non-suspending, and returns only after its effect on `state` is
already visible — `pause(); check(state.value is TimeState.Paused)` can never race. That guarantee,
and how it's kept without a `suspend fun`, is explained in [The reducer](#the-reducer-a-pure-state-machine).

`release()` only cancels this timer's own child job — never the scope it was built with, so a timer
bound to `viewModelScope` can't take the `ViewModel` down with it. `use { }` (`inline fun <T :
Corotimer, R> T.use(block: (T) -> R): R`) runs a block and releases afterwards, success or not —
the `Closeable.use` pattern, without requiring `Corotimer` to implement `Closeable` on every
platform.

### The builder DSL

```kotlin
scope.countdownTimer(startTime = 5.minutes, startFormat = "MM:SS") {
    changeFormatWhen(10.seconds, "SS.LL")
    actionWhen(3.seconds) { playTickSound() }
    onFinish { showTimeUpDialog() }
}
```

`CountdownTimerBuilder`/`StopwatchBuilder` are created fresh on every `countdownTimer`/`stopwatch`
call — there's no shared, mutable builder state to accidentally reuse, so calling either from a
loop or concurrently from multiple threads is safe by construction, not by convention.

`actionWhen` is **edge-triggered**: a threshold already satisfied the instant the timer starts is
armed but never fired, so `actionWhen(startTime) { }` would silently never run — which is exactly
why that specific case (`threshold >= startTime` for a countdown, `threshold <= startOffset` for a
stopwatch) is rejected at configuration time instead of silently doing nothing at runtime.

`changeFormatWhen` is the opposite: **level-triggered**. The active format is always whichever
threshold most tightly bounds the current value, so a threshold may legally equal the start value
— unlike `actionWhen`, there's no "firing" to miss.

### `MonotonicClock`: injectable, sleep-inclusive

```kotlin
public fun interface MonotonicClock {
    public fun elapsedRealtimeMillis(): Long
}
```

This exists instead of using `kotlin.time.TimeSource.Monotonic` directly for one reason:
`TimeSource.Monotonic` maps to `System.nanoTime()` on the JVM and Android, which **stops advancing
during deep sleep**. A 30-minute background countdown built on it would under-count by however
long the device slept. Each platform actual uses a clock that keeps counting through sleep instead:

| Platform | Clock | Why |
|---|---|---|
| JVM | `System.nanoTime()` | No deep-sleep suspend state to lose time across on a desktop/server process — the sleep-inclusive requirement is a mobile-specific concern. |
| Android | `SystemClock.elapsedRealtime()` | Keeps counting through deep sleep, unlike `System.nanoTime()`. |
| iOS | `mach_continuous_time()` | `mach_absolute_time()` has the same stops-during-sleep defect as `nanoTime()`; `NSProcessInfo.systemUptime` has the *opposite* defect — it actively excludes sleep time, under-counting a background countdown the other way. `mach_continuous_time()` is the one API that matches Android's behavior. |

Being an interface (a `fun interface`, so a lambda is enough) also makes it the seam tests use:
pass a clock backed by `TestCoroutineScheduler` and the engine runs in virtual time (see
[Testing philosophy](#testing-philosophy)).

### `TickPolicy`: cadence, never correctness

`Aligned` (the default) wakes the tick loop exactly when the rendered string can change — a unit
rollover, the next unfired `actionWhen`/`changeFormatWhen` threshold, or zero — instead of polling.
`Fixed(interval)` wakes on a plain interval instead, for a UI that can't repaint faster than that.
Either way, **the displayed value is always recomputed from the clock when the loop wakes, never
accumulated from tick counts** — so no policy choice, and no slow `actionWhen`/`onFinish` callback
delaying the *next* wake-up, can make the displayed time drift. `TickPolicy` only ever controls how
often the engine looks, never what it computes.

## The engine

Everything above sits on `internal/engine`, which is one small state machine wired to a coroutine
loop.

### `Phase`: one enum instead of five booleans

```kotlin
internal enum class Phase { IDLE, RUNNING, PAUSED, FINISHED, RELEASED }
```

The design this replaces tracked the same information as five independent, loosely-coupled
booleans (`finished`, `wasStarted`, `stopped`, a nullable job, and an implicit idle case). Every
state bug traced back to two of those disagreeing with each other — the `Finished`-overwritten-by-
`Paused` bug above is one instance of that class. Collapsing it to one enum makes the disagreeing
states unrepresentable: there's no combination of fields to get out of sync, because there's only
one field.

### `EngineState`: one immutable snapshot

```kotlin
internal data class EngineState(
    val phase: Phase,
    val configuredMillis: Long,
    val accumulatedMillis: Long,
    val baseMillis: Long,
    val semanticIndex: Int,
    val firedActions: Set<Int>,
    val laps: List<Lap>,
    val cycle: Int,
    val runId: Long,
    val rendered: TimeState,
)
```

Every field a timer needs — including the already-rendered `TimeState` the public API exposes —
lives in one snapshot, swapped atomically. Keeping `rendered` *inside* the snapshot (rather than
computing it separately when someone reads `state`) is what makes the public state and the
internal bookkeeping impossible to observe out of step with each other: they change together in
one `compareAndSet`, or not at all.

### The reducer: a pure state machine

`Reducer.reduce(current, event, now, config)` touches no clock, no coroutine, and calls no
callback — `now` is a plain `Long` passed in, and side effects are *returned* as a list of `Effect`
values rather than performed. That's what makes it testable with plain assertions
(`ReducerTest.kt` calls it directly with hand-picked timestamps) and it's also load-bearing for
correctness:

```kotlin
private fun dispatch(event: Event): Transition {
    while (true) {
        val current = internalState.value
        val now = clock.elapsedRealtimeMillis()
        val transition = Reducer.reduce(current, event, now, config)
        if (transition.next === current) return transition
        if (internalState.compareAndSet(current, transition.next)) {
            publicState.value = transition.next.rendered
            return transition
        }
    }
}
```

`Engine.dispatch` runs the reducer under a compare-and-set retry loop instead of a mutex. If the
reducer performed a callback (fired `onFinish`, say) *inside* itself, a CAS retry — provoked by a
concurrent caller on another thread — would run that callback once per attempt. Returning effects
and firing them only after the CAS wins (`dispatchAndLaunch`, `runTickEffects`) guarantees each one
runs exactly once, no matter how many times the reducer itself re-runs.

This is also why the public API can be non-suspending and still race-free without a dispatcher:
the swap into `internalState` and the write to the exposed `publicState.value` happen inside the
same synchronous `dispatch` call, so `pause(); state.value` is guaranteed to observe the paused
value — there's no coroutine in between that could be scheduled late.

A transition that changes nothing returns `current` itself (referential identity, not an equal
copy) — that's how callers like `Engine.lap()` distinguish "a lap was recorded" from "this was a
no-op" without a separate boolean.

### `runId`: fencing stale ticks

Every transition that would invalidate an in-flight tick loop (`start`, `reset`, a cycle rollover,
`release`) bumps `runId`. A running `tickLoop(runId)` coroutine carries the id it was launched
with and checks it after every `delay()`:

```kotlin
private suspend fun tickLoop(runId: Long) {
    while (true) {
        val current = internalState.value
        if (current.runId != runId || current.phase != Phase.RUNNING) return
        ...
        val transition = dispatch(Event.Tick(runId))
        ...
    }
}
```

The reducer applies the same check to the event itself (`if (runId != current.runId ...) return
noop(current)`). Together these mean a tick that was already sleeping in `delay()` when `reset()`
ran can wake up, dispatch, and have the reducer discard it — it can never publish a stale `Running`
state over a timer that has since moved on. This is the direct fix for the bug where a tick in
flight during `reset()` could resurrect a state the caller had already left.

### Two `StateFlow`s, one purpose

`Engine` holds an `internalState: MutableStateFlow<EngineState>` (all the bookkeeping — `laps`,
`firedActions`, `runId`, ...) and a separate `publicState: MutableStateFlow<TimeState>` that only
ever mirrors `internalState.value.rendered`. The public one exists so `MutableStateFlow`'s own
conflation (setting `.value` to an equal value is a no-op) de-duplicates emissions at the level
consumers actually care about: a `reset()` on an already-idle timer changes no observable state and
therefore emits nothing, even though internal bookkeeping (like `runId`) did change underneath.

### The nine regression bugs

`RegressionTest.kt` is one named, end-to-end reproduction per bug found in the design this library
replaces — each test recreates the exact scenario that used to be wrong, driven through the real
public API in virtual time, not through internal state directly.

| # | The bug | The fix |
|---|---|---|
| 1 | `pause()` (there: `stop()`) overwrote a finished countdown with `Paused(0)`. | `pause` is a no-op outside `Running`; see [No `stop()`](#no-stop). |
| 2 | `setTime` on a paused engine ignored the already-accumulated elapsed time, so resuming jumped forward by however long the previous run had gone. | `setTime` always rebuilds the snapshot through `freshRun`, which clears `accumulatedMillis` unconditionally. |
| 3 | A tick already in flight when `reset()` fired could still publish a `Running` state afterwards. | The `runId` fence — see [above](#runid-fencing-stale-ticks). |
| 4 | A throwing `onFinish`/`actionWhen` callback had no isolation path and could take the tick loop down. | `Engine.guarded {}` catches everything but `CancellationException` and routes it to `EngineConfig.onError`. |
| 5 | With a coarse format (e.g. `"HH"`), the aligned scheduler only woke up once an hour, so a near-term `actionWhen` could fire up to an hour late. | `TickScheduler.alignedDelay` takes the *minimum* distance across the format boundary, every unfired action, every unfired format threshold, and (for a countdown) zero — not just the format boundary. |
| 6 | A countdown could overshoot zero by up to one whole rendered unit before finishing. | Same fix as #5: zero is one of the candidates `alignedDelay` computes a distance to. |
| 7 | An `actionWhen`/`changeFormatWhen` threshold equal to the start value fired immediately at t=0. | Rejected at configuration time (`require` in the builder) instead of arming a threshold that's already satisfied — see [edge-triggered vs. level-triggered](#the-builder-dsl). |
| 8 | Lapping a paused stopwatch was either disallowed or returned a wrong value. | `Reducer.lap` explicitly allows both `Running` and `Paused`; the split is computed from `elapsedOf`, which already handles a frozen (paused) elapsed time correctly. |
| 9 | Format overflow truncated the most significant digit instead of widening the field. | `SemanticFormatter` never truncates the largest rendered unit — see [Overflow widens](#format-engine). |

## Format engine

`PatternAnalyzer.analyze(pattern) -> Semantic -> SemanticFormatter.format(semantic, millis)` is a
three-stage, direction-agnostic pipeline — it renders a plain millisecond value and knows nothing
about timers, countdowns, or stopwatches.

- **`PatternAnalyzer`** parses a pattern in one left-to-right pass, building the literal/unit
  segment list and the escape handling (`#` drops its own character and un-specials the next one;
  `##` is a literal `#`) together, so escaped characters can never end up misaligned with unit
  offsets. Only the *first* run of a symbol is live — `"HH:MM:HH"` renders hours, minutes, then the
  literal text `HH` — and `validateCombination` rejects patterns that would be ambiguous by omitting
  a middle unit (`"HH:SS"` can't show 90 minutes without lying about what "SS" means).
- **`Semantic`** is the parsed, immutable result: the segment list, each unit's configured width,
  and the pattern's `granularityMillis` — the smallest time delta the pattern can actually
  distinguish, which is what drives `TickScheduler`'s wake-up cadence.
- **`SemanticFormatter`** renders a `Long`. The largest configured unit absorbs everything above
  it (`"MM:SS"` at 90 minutes is `"90:00"`, not `"30:00"` — a pattern that omits hours is asking for
  total minutes) and **overflow widens rather than truncates** (`"HH"` at 100 hours is `"100"`,
  never a truncated `"00"`).

Countdown and stopwatch rendering differ by exactly one adjustment, made in `Reducer.render`, not
in the (reusable, direction-agnostic) formatter itself: a countdown renders with a **ceiling**, the
same "any time left in this unit still counts" rule a kitchen timer uses — 4001..5000ms remaining
all read `"05"` with an `SS` format, only dropping to `"04"` once a full second has actually
elapsed. A stopwatch counts up, so floor is already correct: the first second reads `"00"` as
expected, with no adjustment needed.

## Testing philosophy

`countdownTimer`/`stopwatch` take an injectable `MonotonicClock`. Every end-to-end test in
`commonTest` passes one backed by `kotlinx.coroutines.test.TestCoroutineScheduler`
(`testutil/VirtualClock.kt`) and drives it with `runTest` + `advanceTimeBy` + `runCurrent` — the
*real* `start()` → `delay()` → tick → emit path runs, in virtual time, rather than a test calling an
internal `tick()` function by hand. A test suite built the other way can pass while the actual
coroutine wiring between `start()` and the first emission has never executed once. `ReducerTest`
and `TickSchedulerTest` are the exception on purpose: they test the pure functions directly with
hand-picked timestamps, because that's what they're for — the engine's coroutine plumbing lives in
`Engine.kt` and is covered by the end-to-end tests instead.

`ConcurrencyStressTest` is the other deliberate exception to "always use virtual time": it hammers
the engine from real OS threads via `Dispatchers.Default`, because thread-safety of the CAS loop is
the property under test, and a single-threaded virtual dispatcher can't exercise a race.

## Build configuration

- **`explicitApi()`** — every public declaration needs an explicit visibility modifier, and any
  type that would otherwise leak through inference needs an explicit type. This is a compiler
  check, not a convention to remember, which is exactly why `sample-jvm` compiling at all is a
  useful smoke test: it would fail to compile the moment an `internal` type leaked into a public
  signature.
- **`allWarningsAsErrors`** — a change that compiles with a warning fails the build.
- **detekt**, `buildUponDefaultConfig` plus `config/detekt/detekt.yml`, with three rule
  overrides, each justified in the config file itself rather than suppressed inline:
  `TooGenericExceptionCaught`/`SwallowedException` are off because `Engine.guarded` deliberately
  catches `Throwable` so a user callback can't kill the tick loop; `InjectDispatcher` is off because
  `ConcurrencyStressTest` deliberately hammers the engine with real dispatchers, which is the
  property under test.
- **Publishing**: JVM and Android (`publishLibraryVariants("release")`) targets publish; iOS
  targets are declared (`iosX64`, `iosArm64`, `iosSimulatorArm64`) but not yet verified on real
  hardware or a simulator — see the README's [Platform support](README.md#platform-support)
  section.
