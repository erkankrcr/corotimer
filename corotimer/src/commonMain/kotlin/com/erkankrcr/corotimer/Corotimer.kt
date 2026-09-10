package com.erkankrcr.corotimer

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * A running [CountdownTimer] or [Stopwatch] handle.
 *
 * Every method here is a plain, non-suspending function that returns once its effect on [state]
 * is already visible: `pause(); check(state.value is TimeState.Paused)` never races. Deliberately
 * absent is a `stop()` — the reference API this library replaces used it to mean "pause", which
 * silently overwrote a [TimeState.Finished] countdown with a fresh [TimeState.Paused]. Call
 * [pause] to freeze a running timer and [reset] to return it to its configured value.
 */
public interface Corotimer {

    /** The current state. Never empty of subscribers' first value: a new collector immediately
     * receives whatever is current, including before [start] is ever called. */
    public val state: StateFlow<TimeState>

    /** Starts from [TimeState.Idle] or [TimeState.Finished]. No-op while already [TimeState.Running]. */
    public fun start()

    /** Freezes a [TimeState.Running] timer. No-op otherwise — in particular a
     * [TimeState.Finished] timer stays finished. */
    public fun pause()

    /** Resumes a [TimeState.Paused] timer from where it was frozen. No-op otherwise. */
    public fun resume()

    /** Returns to the value this timer was configured with, discarding any [setTime]. Always
     * legal, including while running. */
    public fun reset()

    /**
     * Re-anchors the timer to [millis] without losing its running/paused/idle phase — the
     * opposite of the common bug where re-timing a paused timer forgets the elapsed time and
     * jumps forward the moment it resumes. Legal in every phase.
     */
    public fun setTime(millis: Long)

    /**
     * Cancels this timer's tick loop permanently. After this call every method is a no-op and
     * [state] stops emitting. Only the timer's own child coroutine job is cancelled — the
     * [kotlinx.coroutines.CoroutineScope] it was created with is left untouched, so a timer bound
     * to `viewModelScope` cannot bring the `ViewModel` down with it.
     *
     * Prefer [use] over calling this directly when the timer's lifetime is scoped to a block.
     */
    public fun release()
}

/** [setTime] taking a [Duration] instead of raw milliseconds. */
public fun Corotimer.setTime(duration: Duration): Unit = setTime(duration.inWholeMilliseconds)

/** Runs [block] with this timer and [releases][Corotimer.release] it afterwards, success or not. */
public inline fun <T : Corotimer, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        release()
    }
}
