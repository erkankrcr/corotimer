package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One named, end-to-end reproduction per bug found in the reference implementation this library
 * replaces. Each test recreates the exact failure scenario that used to be wrong.
 */
class RegressionTest {

    /** stop() (here: pause()) used to overwrite a finished countdown with Paused(0). */
    @Test
    fun bug1_pausing_a_finished_countdown_does_not_resurrect_it_as_paused() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 3.seconds, startFormat = "SS", clock = clock)
        timer.start()
        advanceTimeBy(3_000)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)

        timer.pause()
        assertIs<TimeState.Finished>(timer.state.value, "pause on a finished countdown must be a no-op")
    }

    /** setTime() on a paused engine used to ignore the already-accumulated elapsed time, so
     * resuming immediately jumped forward by however long the previous run had been going. */
    @Test
    fun bug2_setTime_on_paused_engine_ignores_stale_accumulated_elapsed() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.minutes, startFormat = "MM:SS", clock = clock)
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()
        timer.pause()

        timer.setTime(30.seconds.inWholeMilliseconds)
        assertEquals("00:30", timer.state.value.formattedTime)

        timer.resume()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("00:29", timer.state.value.formattedTime, "must not have jumped forward by the stale 2000ms")
    }

    /** A tick already in flight when reset() fired could publish a ghost Running afterwards. */
    @Test
    fun bug3_a_tick_in_flight_during_reset_cannot_publish_a_ghost_running_state() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        advanceTimeBy(999) // a tick is scheduled for t=1000 but has not fired yet
        timer.reset() // invalidates that in-flight tick via the runId fence

        advanceTimeBy(1) // let the stale tick, if it survived, attempt to fire
        runCurrent()
        assertEquals(TimeState.Idle(5_000L, "05", 0), timer.state.value)
    }

    /** A throwing onFinish used to have no isolation path and could take the tick loop down. */
    @Test
    fun bug4_a_throwing_callback_is_isolated_to_onError_not_propagated() = runTest {
        val clock = VirtualClock(testScheduler)
        var caught: Throwable? = null
        val timer = backgroundScope.countdownTimer(
            startTime = 1.seconds,
            startFormat = "SS",
            clock = clock,
            onError = { caught = it },
        ) {
            onFinish { throw IllegalStateException("boom") }
        }
        timer.start()
        advanceTimeBy(1_000)
        runCurrent()

        assertIs<IllegalStateException>(caught)
        assertIs<TimeState.Finished>(timer.state.value)
    }

    /** Aligned + a coarse HH format used to only wake up once an hour, so a 5-minute-out action
     * could fire up to an hour late instead of promptly. */
    @Test
    fun bug5_a_near_term_action_is_not_delayed_by_a_coarse_format_granularity() = runTest {
        val clock = VirtualClock(testScheduler)
        var firedAt = -1L
        val timer = backgroundScope.countdownTimer(startTime = 2.minutes, startFormat = "HH", clock = clock) {
            actionWhen(115.seconds) { firedAt = testScheduler.currentTime }
        }
        timer.start()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(5_000L, firedAt, "must fire promptly at the 5s mark, not up to an hour late")
    }

    /** Aligned also used to let a countdown overshoot zero by up to one whole rendered unit. */
    @Test
    fun bug6_a_countdown_finishes_at_the_exact_configured_duration_not_up_to_one_unit_late() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 90.seconds, startFormat = "HH", clock = clock)
        timer.start()
        advanceTimeBy(90_000)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)
        assertEquals(90_000L, testScheduler.currentTime)
    }

    /** An actionWhen threshold equal to the start value used to fire immediately at t=0. */
    @Test
    fun bug7_an_action_threshold_equal_to_start_time_is_rejected_rather_than_firing_at_t0() = runTest {
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") {
                actionWhen(5.seconds) { error("must never run") }
            }
        }
        assertIs<IllegalArgumentException>(exception)
    }

    /** Lapping a paused stopwatch used to be either disallowed or fall back to a wrong value. */
    @Test
    fun bug8_lap_while_paused_is_allowed_and_returns_the_frozen_split() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()
        advanceTimeBy(2_500)
        runCurrent()
        watch.pause()

        val lap = watch.lap()
        assertEquals(2_500L, lap?.splitMillis)
    }

    /** Format overflow used to truncate the most significant digit instead of widening the field. */
    @Test
    fun bug9_format_overflow_widens_the_field_instead_of_truncating_the_high_digit() = runTest {
        assertEquals("100", TimeFormatter.format("HH", 100 * 3_600_000L))
    }
}
