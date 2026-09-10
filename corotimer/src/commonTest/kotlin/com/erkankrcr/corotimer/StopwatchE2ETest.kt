package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.Recorder
import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StopwatchE2ETest {

    @Test
    fun counts_up_one_emission_per_second() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, watch.state)
        runCurrent()

        watch.start()
        advanceTimeBy(3_100)
        runCurrent()

        assertEquals(listOf("00", "00", "01", "02", "03"), recorder.values.map { it.formattedTime })
    }

    @Test
    fun never_finishes_and_keeps_counting_past_any_bound() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(TimeState.Running(10_000L, "10", 0), watch.state.value)
    }

    @Test
    fun startOffset_is_included_from_the_first_instant() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "MM:SS", startOffset = 1.minutes, clock = clock)
        assertEquals(TimeState.Idle(60_000L, "01:00", 0), watch.state.value)

        watch.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TimeState.Running(65_000L, "01:05", 0), watch.state.value)
    }

    @Test
    fun reset_while_running_returns_to_idle_at_zero() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "MM:SS", clock = clock)
        watch.start()
        advanceTimeBy(5_000)
        runCurrent()

        watch.reset()
        assertEquals(TimeState.Idle(0L, "00:00", 0), watch.state.value)
    }

    @Test
    fun three_pause_cycles_accumulate_elapsed_time_correctly() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS.LLL", clock = clock)
        watch.start()

        advanceTimeBy(1_000)
        runCurrent()
        watch.pause()
        advanceTimeBy(500) // time passes while paused; must not count
        runCurrent()

        watch.resume()
        advanceTimeBy(1_000)
        runCurrent()
        watch.pause()
        advanceTimeBy(500)
        runCurrent()

        watch.resume()
        advanceTimeBy(1_000)
        runCurrent()
        watch.pause()

        assertEquals(TimeState.Paused(3_000L, "03.000", 0), watch.state.value)
    }

    @Test
    fun changeFormatWhen_switches_format_once_elapsed_reaches_the_threshold() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock) {
            changeFormatWhen(3.seconds, "MM:SS")
        }
        watch.start()

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals("02", watch.state.value.formattedTime)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("00:03", watch.state.value.formattedTime)
    }
}
