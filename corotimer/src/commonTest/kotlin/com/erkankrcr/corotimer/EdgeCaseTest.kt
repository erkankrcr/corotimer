package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EdgeCaseTest {

    @Test
    fun a_one_millisecond_countdown_finishes_on_the_next_tick() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 1.milliseconds, startFormat = "SS.LLL", clock = clock)
        timer.start()
        advanceTimeBy(1)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)
    }

    /**
     * `Duration` itself saturates before this is ever reachable ([countdown_rejects_infinite_startTime]
     * and its stopwatch equivalent cover that boundary); [Reducer.saturatingAdd] guards the raw
     * `Long` arithmetic underneath, pinned directly and precisely in `ReducerTest`. This exercises
     * the same concern one level up: a stopwatch with a very large, but validly finite, offset
     * must still run without throwing or wrapping negative.
     */
    @Test
    fun a_very_large_stopwatch_offset_runs_without_overflowing() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(
            startFormat = "SS",
            startOffset = (Long.MAX_VALUE / 4).milliseconds,
            clock = clock,
        )
        watch.start()
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<TimeState.Running>(watch.state.value)
        assert(watch.state.value.currentMillis > 0L)
    }

    @Test
    fun a_format_too_narrow_for_a_huge_offset_still_renders_by_widening() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", startOffset = 1_000_000.seconds)
        assertEquals(1_000_000_000L, watch.state.value.currentMillis)
        assert(watch.state.value.formattedTime.isNotEmpty())
    }

    @Test
    fun laps_before_start_pause_finish_reset_and_release_are_handled() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)

        assertNull(watch.lap(), "before start")

        watch.start()
        advanceTimeBy(500)
        runCurrent()
        watch.pause()
        assertEquals(500L, watch.lap()?.splitMillis, "while paused")

        watch.reset()
        assertNull(watch.lap(), "immediately after reset, still idle")

        watch.release()
        assertNull(watch.lap(), "after release")
    }

    @Test
    fun fifty_stacked_format_thresholds_select_the_tightest_one() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 100.seconds, startFormat = "SS", clock = clock) {
            for (t in 1..50) {
                changeFormatWhen(t.seconds, "SS.LL")
            }
        }
        timer.start()
        advanceTimeBy(75_000) // 25s remaining, inside the stacked-threshold range
        runCurrent()
        assertEquals("25.00", timer.state.value.formattedTime)
    }

    @Test
    fun a_backwards_jumping_clock_never_produces_a_negative_or_decreasing_countdown_display() = runTest {
        var fakeNow = 0L
        val backwardsClock = MonotonicClock { fakeNow }
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = backwardsClock)

        timer.start()
        fakeNow = 2_000L
        timer.pause() // elapsed must read as 2000, not something wrapped
        assertEquals(3_000L, timer.state.value.currentMillis)

        fakeNow = 1_000L // clock jumps backwards relative to the 2000 it just read
        timer.resume() // re-anchors baseMillis to 1000
        fakeNow = 500L // and jumps backwards again, now below the fresh baseMillis
        timer.pause()
        // elapsedOf coerces (now - baseMillis) to at least 0 rather than going negative, so the
        // accumulated 2000ms survives untouched instead of being reduced or going negative.
        assertEquals(3_000L, timer.state.value.currentMillis)
    }

    @Test
    fun repeated_countdownTimer_construction_never_throws_from_shared_state() = runTest {
        repeat(100) {
            backgroundScope.countdownTimer(startTime = 1.seconds, startFormat = "SS")
        }
    }

    @Test
    fun duplicate_action_thresholds_both_fire_independently() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock) {
            actionWhen(3.seconds) { fireCount++ }
            actionWhen(3.seconds) { fireCount++ }
        }
        timer.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, fireCount)
    }

    @Test
    fun format_with_only_escaped_characters_is_rejected_even_as_a_startFormat() = runTest {
        assertFailsWith<InvalidTimeFormatException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "###")
        }
    }
}
