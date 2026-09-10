package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.TickPolicy
import com.erkankrcr.corotimer.internal.format.PatternAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The aligned-delay math in isolation: given a display value and a policy, how long until the
 * tick loop should next wake. [TickPolicy.Aligned]'s whole job is to hit unit boundaries,
 * thresholds and zero exactly — none of that needs a running engine to verify.
 */
class TickSchedulerTest {

    private fun state(semanticIndex: Int = 0, firedActions: Set<Int> = emptySet()) = EngineState(
        phase = Phase.RUNNING,
        configuredMillis = 0L,
        accumulatedMillis = 0L,
        baseMillis = 0L,
        semanticIndex = semanticIndex,
        firedActions = firedActions,
        laps = emptyList(),
        cycle = 0,
        runId = 1L,
        rendered = TestRendered,
    )

    private fun config(
        countDown: Boolean,
        pattern: String,
        actions: List<ActionEntry> = emptyList(),
        extraSemantics: List<SemanticEntry> = emptyList(),
        tickPolicy: TickPolicy = TickPolicy.Aligned,
    ) = EngineConfig(
        countDown = countDown,
        initialMillis = 0L,
        semantics = listOf(SemanticEntry(0L, PatternAnalyzer.analyze(pattern))) + extraSemantics,
        actions = actions,
        tickPolicy = tickPolicy,
        totalCycles = 1,
        onFinish = null,
        onCycleComplete = null,
        onError = {},
    )

    // ---------------------------------------------------------------- unit-boundary alignment

    @Test
    fun stopwatch_seconds_delay_is_distance_to_next_second() {
        val config = config(countDown = false, pattern = "SS")
        assertEquals(700L, TickScheduler.delayMillis(config, state(), display = 4_300L))
    }

    @Test
    fun stopwatch_exactly_on_a_boundary_waits_a_full_unit() {
        val config = config(countDown = false, pattern = "SS")
        assertEquals(1_000L, TickScheduler.delayMillis(config, state(), display = 4_000L))
    }

    @Test
    fun countdown_seconds_delay_is_remainder_into_the_current_second() {
        val config = config(countDown = true, pattern = "SS")
        assertEquals(300L, TickScheduler.delayMillis(config, state(), display = 4_300L))
    }

    @Test
    fun countdown_minutes_granularity_uses_a_60_second_boundary() {
        val config = config(countDown = true, pattern = "MM")
        assertEquals(45_000L, TickScheduler.delayMillis(config, state(), display = 165_000L)) // 2:45 remaining
    }

    @Test
    fun countdown_hours_granularity_uses_a_one_hour_boundary() {
        val config = config(countDown = true, pattern = "HH")
        assertEquals(600_000L, TickScheduler.delayMillis(config, state(), display = 3_600_000L + 600_000L))
    }

    @Test
    fun tenths_granularity_is_100ms() {
        val config = config(countDown = false, pattern = "S.L")
        assertEquals(60L, TickScheduler.delayMillis(config, state(), display = 4_240L))
    }

    @Test
    fun hundredths_granularity_is_10ms() {
        val config = config(countDown = false, pattern = "S.LL")
        assertEquals(6L, TickScheduler.delayMillis(config, state(), display = 4_244L))
    }

    @Test
    fun milliseconds_granularity_is_1ms_every_tick() {
        val config = config(countDown = false, pattern = "S.LLL")
        assertEquals(1L, TickScheduler.delayMillis(config, state(), display = 4_244L))
    }

    // ---------------------------------------------------------------- zero-crossing (countdown finish)

    @Test
    fun countdown_never_overshoots_zero_even_mid_second() {
        val config = config(countDown = true, pattern = "SS")
        assertEquals(400L, TickScheduler.delayMillis(config, state(), display = 400L))
    }

    /**
     * Landing exactly on a positive granularity multiple (remainder 0) is still that boundary's
     * rendered value under the ceiling render (see Reducer.render): a full unit must still elapse
     * before the next change, not a busy-spin 1ms poke.
     */
    @Test
    fun countdown_exactly_on_a_positive_boundary_waits_a_full_unit_not_a_busy_spin() {
        val config = config(countDown = true, pattern = "SS")
        assertEquals(1_000L, TickScheduler.delayMillis(config, state(), display = 3_000L))
    }

    /**
     * `display == 0` while still RUNNING is reachable — `setTime(0)` on a running countdown does
     * exactly this — and must wake immediately so the pending FINISHED transition is processed
     * right away, not after a full spurious granularity as the positive-boundary case above would
     * otherwise suggest.
     */
    @Test
    fun countdown_already_at_zero_wakes_immediately_instead_of_waiting_a_full_unit() {
        val config = config(countDown = true, pattern = "SS")
        assertEquals(1L, TickScheduler.delayMillis(config, state(), display = 0L))
    }

    @Test
    fun aligned_delay_is_never_zero_for_a_countdown() {
        val config = config(countDown = true, pattern = "SS")
        for (display in 0L..2_500L step 137) {
            assertTrue(TickScheduler.delayMillis(config, state(), display) > 0L, "display=$display")
        }
    }

    // ---------------------------------------------------------------- threshold wake-ups

    @Test
    fun aligned_wakes_early_for_an_unfired_action_threshold() {
        // HH-granularity would normally sleep up to 50 minutes for the next hour-digit rollover;
        // an action due in 5s must cut that short instead of firing up to an hour late.
        val action = ActionEntry(thresholdMillis = 6_595_000L) {}
        val config = config(countDown = true, pattern = "HH", actions = listOf(action))
        assertEquals(5_000L, TickScheduler.delayMillis(config, state(), display = 6_600_000L))
    }

    @Test
    fun aligned_ignores_an_already_fired_action_threshold() {
        val action = ActionEntry(thresholdMillis = 6_595_000L) {}
        val config = config(countDown = true, pattern = "HH", actions = listOf(action))
        val fired = state(firedActions = setOf(0))
        assertEquals(3_000_000L, TickScheduler.delayMillis(config, fired, display = 6_600_000L))
    }

    @Test
    fun aligned_ignores_an_action_threshold_already_behind_us() {
        val action = ActionEntry(thresholdMillis = 9_000L) {} // countdown already past this remaining value
        val config = config(countDown = true, pattern = "SS", actions = listOf(action))
        assertEquals(300L, TickScheduler.delayMillis(config, state(), display = 4_300L))
    }

    @Test
    fun aligned_wakes_early_for_a_format_change_threshold() {
        val extra = SemanticEntry(3_000L, PatternAnalyzer.analyze("S.LL"))
        val config = config(countDown = true, pattern = "SS", extraSemantics = listOf(extra))
        // 3200ms remaining, using the coarse SS format still (semanticIndex 0): the format
        // threshold at 3000 is only 200ms away, tighter than the 200ms-to-next-second boundary too.
        assertEquals(200L, TickScheduler.delayMillis(config, state(semanticIndex = 0), display = 3_200L))
    }

    @Test
    fun stopwatch_wakes_early_for_an_upcoming_format_threshold() {
        // Base format is hour-granularity; a finer format is due in 5s, well inside the ~10s
        // still remaining before the current format's own hour boundary would fire a tick anyway.
        val extra = SemanticEntry(3_595_000L, PatternAnalyzer.analyze("HH:MM:SS"))
        val config = config(countDown = false, pattern = "HH", extraSemantics = listOf(extra))
        assertEquals(5_000L, TickScheduler.delayMillis(config, state(semanticIndex = 0), display = 3_590_000L))
    }

    // ---------------------------------------------------------------- Fixed policy

    @Test
    fun fixed_policy_uses_its_configured_interval_regardless_of_format() {
        val config = config(countDown = false, pattern = "SS", tickPolicy = TickPolicy.Fixed(250.milliseconds))
        assertEquals(250L, TickScheduler.delayMillis(config, state(), display = 4_300L))
    }

    @Test
    fun fixed_policy_still_lands_exactly_on_zero_for_a_countdown() {
        val config = config(countDown = true, pattern = "SS", tickPolicy = TickPolicy.Fixed(250.milliseconds))
        assertEquals(120L, TickScheduler.delayMillis(config, state(), display = 120L))
    }

    @Test
    fun fixed_policy_does_not_clip_for_a_stopwatch_near_a_threshold() {
        val config = config(countDown = false, pattern = "SS", tickPolicy = TickPolicy.Fixed(250.milliseconds))
        assertEquals(250L, TickScheduler.delayMillis(config, state(), display = 0L))
    }

    @Test
    fun fixed_policy_never_returns_zero() {
        val config = config(countDown = true, pattern = "SS", tickPolicy = TickPolicy.Fixed(250.milliseconds))
        assertEquals(1L, TickScheduler.delayMillis(config, state(), display = 0L))
    }
}

private val TestRendered = com.erkankrcr.corotimer.TimeState.Idle(0L, "00", 0)
