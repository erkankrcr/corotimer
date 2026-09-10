package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ThresholdActionTest {

    @Test
    fun fires_exactly_once_at_the_right_virtual_moment() = runTest {
        val clock = VirtualClock(testScheduler)
        var firedAt = -1L
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "SS", clock = clock) {
            actionWhen(7.seconds) { firedAt = testScheduler.currentTime }
        }
        timer.start()

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(3_000L, firedAt)
    }

    @Test
    fun multiple_thresholds_fire_in_order_exactly_once_each() = runTest {
        val clock = VirtualClock(testScheduler)
        val fired = mutableListOf<Int>()
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "SS", clock = clock) {
            actionWhen(8.seconds) { fired += 8 }
            actionWhen(5.seconds) { fired += 5 }
            actionWhen(2.seconds) { fired += 2 }
        }
        timer.start()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(8, 5, 2), fired)
    }

    /** A threshold crossed between two ticks (Fixed policy, coarse interval) must still fire once. */
    @Test
    fun threshold_crossed_between_two_ticks_still_fires_on_the_next_one() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(
            startTime = 10.seconds,
            startFormat = "SS",
            clock = clock,
            tickPolicy = TickPolicy.Fixed(3.seconds),
        ) {
            actionWhen(9.seconds) { fireCount++ } // strictly inside the first 3s tick window
        }
        timer.start()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, fireCount)
    }

    @Test
    fun does_not_refire_across_a_pause_and_resume() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "SS", clock = clock) {
            actionWhen(7.seconds) { fireCount++ }
        }
        timer.start()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, fireCount)

        timer.pause()
        timer.resume()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, fireCount, "resuming must not re-arm an already-fired threshold")
    }

    @Test
    fun reset_rearms_a_threshold_so_it_can_fire_again() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "SS", clock = clock) {
            actionWhen(7.seconds) { fireCount++ }
        }
        timer.start()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, fireCount)

        timer.reset()
        timer.start()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, fireCount)
    }

    @Test
    fun twenty_stacked_thresholds_all_fire_exactly_once() = runTest {
        val clock = VirtualClock(testScheduler)
        val fired = mutableSetOf<Int>()
        val timer = backgroundScope.countdownTimer(startTime = 25.seconds, startFormat = "SS", clock = clock) {
            for (t in 1..20) {
                actionWhen(t.seconds) { fired += t }
            }
        }
        timer.start()
        advanceTimeBy(25_000)
        runCurrent()

        assertEquals((1..20).toSet(), fired)
    }
}
