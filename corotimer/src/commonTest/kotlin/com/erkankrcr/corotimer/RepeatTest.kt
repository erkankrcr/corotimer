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

class RepeatTest {

    @Test
    fun repeat_three_runs_three_full_cycles_then_finishes() = runTest {
        val clock = VirtualClock(testScheduler)
        val cycles = mutableListOf<Int>()
        var finished = 0
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock) {
            repeat(3)
            onCycleComplete { cycles.add(it) }
            onFinish { finished++ }
        }
        timer.start()

        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(listOf(0, 1), cycles, "onCycleComplete must fire after cycle 0 and cycle 1, not after the last one")
        assertEquals(1, finished)
        assertIs<TimeState.Finished>(timer.state.value)
        assertEquals(2, timer.state.value.cycle)
    }

    @Test
    fun cycle_number_is_visible_on_every_emission() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 1.seconds, startFormat = "SS", clock = clock) {
            repeat(2)
        }
        val recorder = Recorder(backgroundScope, timer.state)
        runCurrent()
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()

        // Idle(cycle0), Running(cycle0) at start, Running(cycle1) at the cycle rollover, Finished(cycle1).
        // There is no separate "display=0, cycle=0" emission: the reducer rolls straight into the
        // next cycle's fresh render on the same tick that reaches zero.
        assertEquals(listOf(0, 0, 1, 1), recorder.values.map { it.cycle })
    }

    @Test
    fun repeatForever_keeps_ticking_past_a_hundred_virtual_cycles() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 1.seconds, startFormat = "SS", clock = clock) {
            repeatForever()
        }
        timer.start()

        advanceTimeBy(100_000)
        runCurrent()

        assertIs<TimeState.Running>(timer.state.value)
        assertEquals(100, timer.state.value.cycle)
    }

    @Test
    fun pause_mid_cycle_freezes_within_that_cycle() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock) {
            repeat(3)
        }
        timer.start()
        advanceTimeBy(2_500) // into cycle 1, 500ms elapsed there
        runCurrent()
        timer.pause()

        assertEquals(1, timer.state.value.cycle)
        assertIs<TimeState.Paused>(timer.state.value)
    }

    @Test
    fun reset_mid_repeat_returns_to_cycle_zero() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock) {
            repeat(5)
        }
        timer.start()
        advanceTimeBy(5_000)
        runCurrent()
        timer.reset()

        assertEquals(TimeState.Idle(2_000L, "02", 0), timer.state.value)
    }

    @Test
    fun actions_refire_on_every_cycle() = runTest {
        val clock = VirtualClock(testScheduler)
        var fireCount = 0
        val timer = backgroundScope.countdownTimer(startTime = 2.seconds, startFormat = "SS", clock = clock) {
            repeat(3)
            actionWhen(1.seconds) { fireCount++ }
        }
        timer.start()
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(3, fireCount)
    }
}
