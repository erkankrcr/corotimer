package com.erkankrcr.corotimer

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The one deliberately non-virtual test in the suite: [MonotonicClock.System] driving a real
 * countdown against the real JVM scheduler. Every other test uses [com.erkankrcr.corotimer.testutil.VirtualClock]
 * for determinism; this one exists purely to catch a wiring mistake that only a real clock and a
 * real dispatcher could expose (e.g. accidentally never calling `delay()` at all). Bounds are
 * generous specifically so this does not become a flaky CI test.
 */
class RealClockSmokeTest {

    @Test
    fun a_300ms_countdown_against_the_real_clock_finishes_within_a_generous_window() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val timer = this.countdownTimer(startTime = 300.milliseconds, startFormat = "SS.LLL")

        timer.start()
        while (timer.state.value !is TimeState.Finished) {
            delay(10)
        }
        val elapsed = start.elapsedNow()

        assertTrue(elapsed.inWholeMilliseconds in 250..1_500, "expected roughly 300ms, took $elapsed")
        timer.release()
    }
}
