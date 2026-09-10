package com.erkankrcr.corotimer

import kotlin.time.Duration

/**
 * Controls how often the tick loop wakes up.
 *
 * Note that this only affects the *cadence* of emissions. The displayed time is always recomputed
 * from the monotonic clock, never accumulated from tick counts, so no policy can make the timer
 * drift.
 */
public sealed interface TickPolicy {

    /**
     * Wake exactly when something can change: when the smallest rendered unit rolls over, when
     * the next `actionWhen` or `changeFormatWhen` threshold is due, or when a countdown reaches
     * zero — whichever comes first.
     *
     * This is the default. It aligns wake-ups to unit boundaries, so a seconds display never
     * skips or repeats a value because of the phase at which the timer happened to start, and a
     * countdown finishes at the exact zero crossing rather than up to one unit late.
     */
    public data object Aligned : TickPolicy

    /**
     * Wake every [interval], regardless of what the format can display.
     *
     * Useful to deliberately coarsen a millisecond format for a UI that cannot repaint at
     * 1000 Hz. A countdown still lands exactly on zero rather than overshooting by up to one
     * interval.
     *
     * @throws IllegalArgumentException if [interval] is not strictly positive or is infinite.
     */
    public data class Fixed(public val interval: Duration) : TickPolicy {
        init {
            require(interval.isPositive() && interval.isFinite()) {
                "tick interval must be positive and finite, was $interval"
            }
        }
    }
}
