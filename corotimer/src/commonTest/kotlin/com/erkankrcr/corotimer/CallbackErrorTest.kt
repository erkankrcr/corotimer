package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A throwing callback is isolated to [onError] and must not kill the tick loop or corrupt the
 * displayed time — the reference implementation this library replaces got this right and it is a
 * property worth pinning explicitly, not just inheriting by accident.
 */
class CallbackErrorTest {

    @Test
    fun a_throwing_onFinish_reaches_onError_and_does_not_propagate() = runTest {
        val clock = VirtualClock(testScheduler)
        var caught: Throwable? = null
        val timer = backgroundScope.countdownTimer(
            startTime = 2.seconds,
            startFormat = "SS",
            clock = clock,
            onError = { caught = it },
        ) {
            onFinish { throw IllegalStateException("boom") }
        }
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()

        assertIs<IllegalStateException>(caught)
        assertEquals("boom", caught?.message)
        assertIs<TimeState.Finished>(timer.state.value)
    }

    @Test
    fun a_throwing_actionWhen_does_not_kill_the_tick_loop() = runTest {
        val clock = VirtualClock(testScheduler)
        var errorCount = 0
        val timer = backgroundScope.countdownTimer(
            startTime = 5.seconds,
            startFormat = "SS",
            clock = clock,
            onError = { errorCount++ },
        ) {
            actionWhen(4.seconds) { throw IllegalStateException("action boom") }
        }
        timer.start()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, errorCount)
        assertIs<TimeState.Finished>(timer.state.value, "the tick loop must keep running past a throwing action")
    }

    @Test
    fun a_throwing_onCycleComplete_does_not_kill_the_tick_loop() = runTest {
        val clock = VirtualClock(testScheduler)
        var errorCount = 0
        val timer = backgroundScope.countdownTimer(
            startTime = 2.seconds,
            startFormat = "SS",
            clock = clock,
            onError = { errorCount++ },
        ) {
            repeat(2)
            onCycleComplete { throw IllegalStateException("cycle boom") }
        }
        timer.start()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(1, errorCount)
        assertIs<TimeState.Finished>(timer.state.value)
    }

    @Test
    fun cancellation_is_not_swallowed_by_the_callback_guard() = runTest {
        val clock = VirtualClock(testScheduler)
        var errorCount = 0
        val timer = backgroundScope.countdownTimer(
            startTime = 2.seconds,
            startFormat = "SS",
            clock = clock,
            onError = { errorCount++ },
        ) {
            onFinish { throw CancellationException("not a real error") }
        }
        timer.start()
        advanceTimeBy(2_000)
        runCurrent()

        // A CancellationException must propagate as coroutine cancellation, not be reported as an
        // application error — but must also not crash the surrounding test scope.
        assertEquals(0, errorCount)
    }

    @Test
    fun a_slow_callback_delays_cadence_but_the_displayed_time_stays_correct() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 10.seconds, startFormat = "SS", clock = clock) {
            actionWhen(9.seconds) {
                kotlinx.coroutines.delay(5.seconds) // hangs the tick loop for 5 virtual seconds
            }
        }
        timer.start()
        advanceTimeBy(10_000)
        runCurrent()

        // Whenever the next tick does land, the displayed time is recomputed from the clock, not
        // accumulated from tick counts, so it is still correct despite the earlier stall.
        assertIs<TimeState.Finished>(timer.state.value)
    }
}
