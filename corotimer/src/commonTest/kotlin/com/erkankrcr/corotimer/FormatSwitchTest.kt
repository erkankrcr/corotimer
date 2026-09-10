package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.Recorder
import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class FormatSwitchTest {

    @Test
    fun format_switches_at_the_threshold_and_the_new_pattern_applies_from_that_emission_on() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "MM:SS", clock = clock) {
            changeFormatWhen(3.seconds, "SS.LLL")
        }
        timer.start()

        advanceTimeBy(7_000) // 3000ms remaining exactly: threshold reached
        runCurrent()
        assertEquals("03.000", timer.state.value.formattedTime)

        advanceTimeBy(500)
        runCurrent()
        assertEquals("02.500", timer.state.value.formattedTime)
    }

    @Test
    fun cadence_changes_along_with_the_format() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 3.seconds, startFormat = "MM:SS", clock = clock) {
            changeFormatWhen(2.seconds, "SS.LLL")
        }
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()

        // Coarse MM:SS cadence for the first second (one emission at the 1s mark)...
        advanceTimeBy(1_000)
        runCurrent()
        val countAfterFirstSecond = recorder.values.size

        // ...then millisecond cadence once past the 2-second threshold.
        advanceTimeBy(1_000) // now exactly at the 2s-remaining threshold
        runCurrent()
        advanceTimeBy(2) // two more millisecond-cadence emissions expected
        runCurrent()
        val countAfterSwitch = recorder.values.size

        check(countAfterSwitch - countAfterFirstSecond >= 3) {
            "expected several millisecond-cadence emissions after the format switch, " +
                "got ${countAfterSwitch - countAfterFirstSecond}"
        }
    }

    @Test
    fun stopwatch_format_switches_upward_as_elapsed_grows() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock) {
            changeFormatWhen(90.seconds, "MM:SS")
        }
        watch.start()

        advanceTimeBy(89_000)
        runCurrent()
        assertEquals("89", watch.state.value.formattedTime)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("01:30", watch.state.value.formattedTime)
    }

    @Test
    fun threshold_equal_to_start_time_is_allowed_and_applies_immediately() = runTest {
        val clock = VirtualClock(testScheduler)
        // Level-triggered: unlike actionWhen, a changeFormatWhen threshold may equal startTime.
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "MM:SS", clock = clock) {
            changeFormatWhen(5.seconds, "SS.LLL")
        }
        assertEquals("05.000", timer.state.value.formattedTime)
    }
}
