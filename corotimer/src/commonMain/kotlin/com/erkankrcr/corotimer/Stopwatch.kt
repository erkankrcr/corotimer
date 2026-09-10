package com.erkankrcr.corotimer

/**
 * A [Corotimer] that counts up indefinitely from a configured offset and can record [Lap]s.
 *
 * Build one with [stopwatch]. Never emits [TimeState.Finished] — a stopwatch has no end.
 */
public interface Stopwatch : Corotimer {

    /** Laps recorded so far, oldest first. Cleared by [reset]. */
    public val laps: List<Lap>

    /**
     * Records a lap at the current elapsed time and returns it, or `null` if this stopwatch has
     * never been started ([TimeState.Idle]). Legal while [TimeState.Running] or
     * [TimeState.Paused] — pausing first to capture an exact split is a normal use, not an error.
     */
    public fun lap(): Lap?
}
