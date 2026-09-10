package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.Recorder
import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The tick loop end to end: `start()` really does `delay()` under a coroutine dispatcher and
 * really does emit through [Corotimer.state] over virtual time. The reference implementation this
 * library replaces had zero tests exercising this path — its entire suite drove the reducer by
 * hand and never once let a `CoroutineScope` actually schedule a tick.
 */
class CountdownE2ETest {

    @Test
    fun counts_down_one_emission_per_second_to_finished() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        timer.start()
        advanceTimeBy(5_100)
        runCurrent()

        val formatted = recorder.values.map { it.formattedTime }
        assertEquals(listOf("05", "05", "04", "03", "02", "01", "00"), formatted)
        assertIs<TimeState.Finished>(recorder.values.last())
    }

    @Test
    fun emissions_are_spaced_one_second_apart_in_virtual_time() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 3.seconds, startFormat = "SS", clock = clock)
        val timestamps = mutableListOf<Long>()

        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()

        var previousCount = recorder.values.size
        repeat(3) {
            advanceTimeBy(1_000)
            runCurrent()
            timestamps += testScheduler.currentTime
            check(recorder.values.size > previousCount) { "expected a new emission by ${testScheduler.currentTime}" }
            previousCount = recorder.values.size
        }
        assertEquals(listOf(1_000L, 2_000L, 3_000L), timestamps)
    }

    @Test
    fun sub_second_format_emits_one_hundred_times_over_one_hundred_virtual_milliseconds() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 1.seconds, startFormat = "SS.LLL", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        timer.start()
        advanceTimeBy(100)
        runCurrent()

        // Idle at construction, Running the instant start() fires, then one per elapsed millisecond.
        assertEquals(102, recorder.values.size)
    }

    @Test
    fun fixed_policy_ticks_on_its_configured_interval() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(
            startTime = 1.seconds,
            startFormat = "SS.LLL",
            clock = clock,
            tickPolicy = TickPolicy.Fixed(250.milliseconds),
        )
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()

        timer.start()
        advanceTimeBy(1_000)
        runCurrent()

        // Idle, Running at start, then 4 ticks of 250ms landing exactly at 1000ms (finish).
        assertEquals(6, recorder.values.size)
        assertIs<TimeState.Finished>(recorder.values.last())
    }

    /** Bug 6: the countdown must finish at the exact zero crossing, not up to one unit late. */
    @Test
    fun finishes_at_exactly_the_configured_duration() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()

        advanceTimeBy(4_999)
        runCurrent()
        assertIs<TimeState.Running>(recorder.values.last())

        advanceTimeBy(1)
        runCurrent()
        assertIs<TimeState.Finished>(recorder.values.last())
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun onFinish_fires_exactly_once() = runTest {
        val clock = VirtualClock(testScheduler)
        var finishCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock) {
            onFinish { finishCount++ }
        }
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, finishCount)
    }

    @Test
    fun no_emissions_after_release() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()
        advanceTimeBy(1_000)
        runCurrent()
        val countAtRelease = recorder.values.size

        timer.release()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(countAtRelease, recorder.values.size)
    }

    @Test
    fun pause_then_resume_continues_from_the_frozen_value() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS.LLL", clock = clock)
        timer.start()

        advanceTimeBy(2_300)
        runCurrent()
        timer.pause()
        assertEquals(TimeState.Paused(2_700L, "02.700", 0), timer.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(TimeState.Paused(2_700L, "02.700", 0), timer.state.value)

        timer.resume()
        advanceTimeBy(2_700)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)
        // Virtual time keeps advancing while paused too (2300 + 10000 + 2700): pausing freezes
        // the timer's own accounting, not the ambient clock other code might also be using.
        assertEquals(2_300L + 10_000L + 2_700L, testScheduler.currentTime)
    }

    @Test
    fun hh_format_with_aligned_policy_does_not_delay_a_finish_by_up_to_an_hour() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 90.seconds, startFormat = "HH", clock = clock)
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()

        advanceTimeBy(90_000)
        runCurrent()
        assertIs<TimeState.Finished>(recorder.values.last())
        assertEquals(90_000L, testScheduler.currentTime)
    }
}
