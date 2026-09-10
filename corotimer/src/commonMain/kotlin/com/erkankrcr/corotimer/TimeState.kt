package com.erkankrcr.corotimer

/**
 * The observable state of a [CountdownTimer] or a [Stopwatch].
 *
 * Countdowns and stopwatches share this one hierarchy on purpose: a UI can `when` over it once
 * and render either kind without casting. A [Stopwatch] simply never emits [Finished].
 *
 * Every variant carries a usable [currentMillis] and [formattedTime] — including [Idle] and
 * [Finished] — so a screen can render the timer before it has ever been started without
 * special-casing.
 */
public sealed interface TimeState {

    /**
     * Remaining time for a countdown, elapsed time for a stopwatch. Never negative.
     */
    public val currentMillis: Long

    /**
     * [currentMillis] rendered with the format that is active at this instant. Never empty.
     */
    public val formattedTime: String

    /**
     * Zero-based repeat cycle. Always `0` unless [CountdownTimerBuilder.repeat] or
     * [CountdownTimerBuilder.repeatForever] was configured.
     */
    public val cycle: Int

    /** `true` only in [Running]. */
    public val isRunning: Boolean get() = this is Running

    /** `true` only in [Finished]. */
    public val isFinished: Boolean get() = this is Finished

    /** `true` in [Running] and [Paused] — the timer has been started and is not done. */
    public val isActive: Boolean get() = this is Running || this is Paused

    /** Never started, or returned to the configured value by [Corotimer.reset]. */
    public data class Idle(
        override val currentMillis: Long,
        override val formattedTime: String,
        override val cycle: Int = 0,
    ) : TimeState

    /** The tick loop is live and [currentMillis] is advancing. */
    public data class Running(
        override val currentMillis: Long,
        override val formattedTime: String,
        override val cycle: Int = 0,
    ) : TimeState

    /** Frozen by [Corotimer.pause]; [currentMillis] holds the value at the moment of the pause. */
    public data class Paused(
        override val currentMillis: Long,
        override val formattedTime: String,
        override val cycle: Int = 0,
    ) : TimeState

    /**
     * A countdown reached zero and every configured cycle has run.
     *
     * Terminal: only [Corotimer.reset], [Corotimer.setTime] or [Corotimer.start] leaves this
     * state. In particular [Corotimer.pause] does **not**.
     */
    public data class Finished(
        override val formattedTime: String,
        override val cycle: Int = 0,
    ) : TimeState {
        override val currentMillis: Long get() = 0L
    }
}
