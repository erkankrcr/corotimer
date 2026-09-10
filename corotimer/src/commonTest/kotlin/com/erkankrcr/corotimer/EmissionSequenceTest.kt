package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.Recorder
import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The public [TimeState] stream never repeats a value it already holds, and a late subscriber
 * always sees the current value immediately — both are load-bearing for a UI that just wants to
 * `collectAsState()` without deduplicating itself.
 */
class EmissionSequenceTest {

    @Test
    fun idle_is_emitted_exactly_once_no_matter_how_many_collectors_join_late() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)

        val early = Recorder(backgroundScope, timer.state)
        runCurrent()
        val late = Recorder(backgroundScope, timer.state)
        runCurrent()

        val expected = listOf<TimeState>(TimeState.Idle(5_000L, "05", 0))
        assertEquals(expected, early.values)
        assertEquals(expected, late.values, "a late collector must see the current value immediately")
    }

    @Test
    fun repeated_pause_calls_only_emit_once() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        timer.start()
        advanceTimeBy(500)
        runCurrent()
        val countBeforePauses = recorder.values.size

        repeat(5) { timer.pause() }
        runCurrent()
        assertEquals(countBeforePauses + 1, recorder.values.size, "five pauses must look like one transition")
    }

    @Test
    fun a_redundant_reset_on_an_already_idle_timer_does_not_re_emit() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        val countBefore = recorder.values.size
        timer.reset()
        timer.reset()
        assertEquals(countBefore, recorder.values.size)
    }

    @Test
    fun no_two_consecutive_emissions_are_structurally_equal() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        timer.start()
        advanceTimeBy(5_000)
        runCurrent()
        timer.pause()
        timer.reset()

        for (i in 1 until recorder.values.size) {
            assertIs<TimeState>(recorder.values[i])
            check(recorder.values[i] != recorder.values[i - 1]) {
                "consecutive duplicate at index $i: ${recorder.values[i]}"
            }
        }
    }
}
