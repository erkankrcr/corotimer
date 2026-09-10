package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.TickPolicy

/**
 * Computes how long the tick loop should sleep before its next wake-up.
 *
 * The displayed time is always recomputed from the monotonic clock when the loop wakes, never
 * accumulated from tick counts, so no policy here can make the timer drift — this only controls
 * *cadence*, not correctness.
 */
internal object TickScheduler {

    private const val MIN_DELAY_MILLIS = 1L

    fun delayMillis(config: EngineConfig, state: EngineState, display: Long): Long {
        val raw = when (val policy = config.tickPolicy) {
            TickPolicy.Aligned -> alignedDelay(config, state, display)
            is TickPolicy.Fixed -> fixedDelay(config, display, policy.interval.inWholeMilliseconds)
        }
        return raw.coerceAtLeast(MIN_DELAY_MILLIS)
    }

    /**
     * Wakes exactly when something can change: the smallest rendered unit rolling over, the
     * nearest unfired `actionWhen`/`changeFormatWhen` threshold, or a countdown reaching zero —
     * whichever is closest.
     */
    private fun alignedDelay(config: EngineConfig, state: EngineState, display: Long): Long {
        // A countdown already at or past zero (e.g. `setTime(0)` while running) must wake up
        // immediately so the pending FINISHED transition can be processed — not wait out a full
        // granularity the way landing exactly on a positive boundary correctly does.
        if (config.countDown && display <= 0L) return MIN_DELAY_MILLIS

        val semantic = config.semantics[state.semanticIndex].semantic
        val granularity = semantic.granularityMillis
        val remainder = display % granularity
        var best = if (config.countDown) {
            // The render is ceiling-based (see Reducer.render): display in (v-1)*g..v*g all
            // shows the same value. Landing exactly on a multiple (remainder 0) is itself still
            // that boundary value, so a full granularity must still elapse; landing mid-range
            // needs only the remainder to reach the next-lower multiple.
            if (remainder == 0L) granularity else remainder
        } else {
            // Distance until `display` crosses the next-higher multiple of granularity.
            granularity - remainder
        }

        if (config.countDown) {
            distanceTo(config.countDown, display, 0L)?.let { if (it < best) best = it }
        }
        for (entry in config.semantics) {
            distanceTo(config.countDown, display, entry.thresholdMillis)?.let { if (it < best) best = it }
        }
        config.actions.forEachIndexed { index, entry ->
            if (index !in state.firedActions) {
                distanceTo(config.countDown, display, entry.thresholdMillis)?.let { if (it < best) best = it }
            }
        }
        return best
    }

    /** Ticks on a fixed cadence, only tightened so a countdown never overshoots zero. */
    private fun fixedDelay(config: EngineConfig, display: Long, intervalMillis: Long): Long {
        var best = intervalMillis
        if (config.countDown && display < best) best = display
        return best
    }

    /**
     * Milliseconds of elapsed time until `display` reaches [threshold], or `null` if [threshold]
     * has already been passed (a countdown at or below it, or a stopwatch at or above it).
     */
    private fun distanceTo(countDown: Boolean, display: Long, threshold: Long): Long? = if (countDown) {
        if (display > threshold) display - threshold else null
    } else {
        if (display < threshold) threshold - display else null
    }
}
