package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.Lap
import com.erkankrcr.corotimer.TickPolicy
import com.erkankrcr.corotimer.TimeState
import com.erkankrcr.corotimer.internal.format.Semantic

/**
 * The engine's phase.
 *
 * The reference implementation this library replaces tracked the same information with five
 * loosely-coupled booleans (`finished`, `wasStarted`, `stopped`, a nullable job and an implicit
 * idle case). Every state bug in it came from two of those disagreeing — most visibly, pausing a
 * finished countdown flipped it back to "paused". One enum makes those states unrepresentable.
 */
internal enum class Phase { IDLE, RUNNING, PAUSED, FINISHED, RELEASED }

/** A format that becomes active once the displayed time passes [thresholdMillis]. */
internal class SemanticEntry(val thresholdMillis: Long, val semantic: Semantic)

/** A one-shot callback that fires once the displayed time passes [thresholdMillis]. */
internal class ActionEntry(val thresholdMillis: Long, val action: suspend () -> Unit)

/**
 * Everything a timer is configured with. Immutable, shared by every snapshot, never mutated after
 * the builder hands it over.
 *
 * @property countDown `true` for a countdown, `false` for a stopwatch.
 * @property initialMillis the countdown duration, or the stopwatch start offset. `reset()` returns
 *   to this value even after `setTime()` changed it.
 * @property semantics format thresholds, pre-sorted into selection order: ascending for a
 *   countdown, descending for a stopwatch, so the first match is always the right one.
 * @property actions threshold callbacks in the same order as [SemanticEntry] sorting.
 * @property totalCycles how many times a countdown runs; [Int.MAX_VALUE] means forever.
 */
internal class EngineConfig(
    val countDown: Boolean,
    val initialMillis: Long,
    val semantics: List<SemanticEntry>,
    val actions: List<ActionEntry>,
    val tickPolicy: TickPolicy,
    val totalCycles: Int,
    val onFinish: (suspend () -> Unit)?,
    val onCycleComplete: (suspend (Int) -> Unit)?,
    val onError: (Throwable) -> Unit,
)

/**
 * One atomic snapshot of the whole engine.
 *
 * [rendered] — the value consumers observe — lives inside the snapshot rather than in a second
 * flow. That is what makes the public state and the internal bookkeeping impossible to observe
 * out of step: they are swapped together by a single `compareAndSet`.
 *
 * @property runId bumped by every transition that invalidates a running tick loop. A tick carries
 *   the id it was launched with, and the reducer ignores any tick whose id no longer matches, so a
 *   tick that was already in flight when `reset()` ran can never publish a stale `Running`.
 */
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

/** Input to [Reducer.reduce]. */
internal sealed interface Event {
    data object Start : Event
    data object Pause : Event
    data object Resume : Event
    data object Reset : Event
    data object Release : Event
    data object Lap : Event
    data class SetTime(val millis: Long) : Event
    data class Tick(val runId: Long) : Event
}

/**
 * Something the engine must do as a consequence of a transition.
 *
 * Effects are *returned* by the reducer rather than performed inside it. The CAS loop may retry,
 * re-running the reducer; performing a callback inside would fire it once per attempt. Only the
 * attempt that wins the `compareAndSet` gets to run its effects, so each fires exactly once.
 */
internal sealed interface Effect {
    data class LaunchTick(val runId: Long) : Effect
    data class FireAction(val index: Int) : Effect
    data object FireOnFinish : Effect
    data class FireOnCycleComplete(val cycle: Int) : Effect
}

internal class Transition(val next: EngineState, val effects: List<Effect>)
