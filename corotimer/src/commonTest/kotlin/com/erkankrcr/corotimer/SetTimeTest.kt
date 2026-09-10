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

class SetTimeTest {

    @Test
    fun setTime_while_idle_updates_the_displayed_value_without_starting() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.setTime(1_000L)
        assertEquals(TimeState.Idle(1_000L, "01", 0), timer.state.value)
    }

    @Test
    fun setTime_while_running_re_anchors_without_a_jump() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        advanceTimeBy(1_000)
        runCurrent()

        timer.setTime(10_000L)
        assertEquals(TimeState.Running(10_000L, "10", 0), timer.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("09", timer.state.value.formattedTime)
    }

    /** Bug 2, at the public API level: the accumulated elapsed time must not leak past setTime. */
    @Test
    fun setTime_while_paused_discards_the_previously_accumulated_elapsed() = runTest {
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
        assertEquals("00:29", timer.state.value.formattedTime)
    }

    @Test
    fun setTime_on_a_finished_countdown_moves_it_to_paused() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock)
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)

        timer.setTime(5_000L)
        assertIs<TimeState.Paused>(timer.state.value)
        assertEquals(5_000L, timer.state.value.currentMillis)
    }

    @Test
    fun setTime_on_a_released_timer_is_a_noop() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.release()
        val before = timer.state.value
        timer.setTime(1_000L)
        assertEquals(before, timer.state.value)
    }

    @Test
    fun setTime_of_zero_finishes_on_the_next_tick() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.setTime(0L)
        advanceTimeBy(1)
        runCurrent()
        assertIs<TimeState.Finished>(timer.state.value)
    }

    @Test
    fun setTime_clears_previously_fired_actions_so_they_can_fire_again() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock) {
            actionWhen(2.seconds) { fireCount++ }
        }
        timer.start()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(1, fireCount)

        timer.setTime(5_000L)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(2, fireCount)
    }
}
