package com.erkankrcr.corotimer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BuilderValidationTest {

    // ---------------------------------------------------------------- countdownTimer(startTime, startFormat)

    @Test
    fun countdown_rejects_zero_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = Duration.ZERO, startFormat = "SS")
        }
    }

    @Test
    fun countdown_rejects_negative_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = (-1).seconds, startFormat = "SS")
        }
    }

    @Test
    fun countdown_rejects_infinite_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = Duration.INFINITE, startFormat = "SS")
        }
    }

    @Test
    fun countdown_rejects_a_blank_startFormat() = runTest {
        assertFailsWith<InvalidTimeFormatException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "   ")
        }
    }

    @Test
    fun countdown_rejects_an_unparseable_startFormat() = runTest {
        assertFailsWith<InvalidTimeFormatException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "HH:SS")
        }
    }

    // ---------------------------------------------------------------- countdown changeFormatWhen / actionWhen

    @Test
    fun countdown_changeFormatWhen_threshold_may_equal_startTime() = runTest {
        backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "MM:SS") {
            changeFormatWhen(5.seconds, "SS")
        }
    }

    @Test
    fun countdown_changeFormatWhen_rejects_a_threshold_past_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "MM:SS") {
                changeFormatWhen(6.seconds, "SS")
            }
        }
    }

    @Test
    fun countdown_changeFormatWhen_rejects_a_negative_threshold() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "MM:SS") {
                changeFormatWhen((-1).seconds, "SS")
            }
        }
    }

    /** Bug 7: an action threshold equal to startTime would never fire, so it is rejected outright. */
    @Test
    fun countdown_actionWhen_rejects_a_threshold_equal_to_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") {
                actionWhen(5.seconds) {}
            }
        }
    }

    @Test
    fun countdown_actionWhen_rejects_a_threshold_past_startTime() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") {
                actionWhen(6.seconds) {}
            }
        }
    }

    @Test
    fun countdown_actionWhen_rejects_a_negative_threshold() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") {
                actionWhen((-1).seconds) {}
            }
        }
    }

    @Test
    fun countdown_actionWhen_accepts_a_threshold_just_below_startTime() = runTest {
        backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") {
            actionWhen(4_999.milliseconds) {}
        }
    }

    // ---------------------------------------------------------------- repeat / repeatForever

    @Test
    fun repeat_rejects_zero() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") { repeat(0) }
        }
    }

    @Test
    fun repeat_rejects_negative() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS") { repeat(-1) }
        }
    }

    // ---------------------------------------------------------------- TickPolicy.Fixed

    @Test
    fun fixed_policy_rejects_zero_interval() {
        assertFailsWith<IllegalArgumentException> { TickPolicy.Fixed(Duration.ZERO) }
    }

    @Test
    fun fixed_policy_rejects_negative_interval() {
        assertFailsWith<IllegalArgumentException> { TickPolicy.Fixed((-1).seconds) }
    }

    @Test
    fun fixed_policy_rejects_infinite_interval() {
        assertFailsWith<IllegalArgumentException> { TickPolicy.Fixed(Duration.INFINITE) }
    }

    // ---------------------------------------------------------------- stopwatch(startFormat, startOffset)

    @Test
    fun stopwatch_rejects_a_negative_startOffset() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.stopwatch(startFormat = "SS", startOffset = (-1).seconds)
        }
    }

    @Test
    fun stopwatch_rejects_an_infinite_startOffset() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.stopwatch(startFormat = "SS", startOffset = Duration.INFINITE)
        }
    }

    @Test
    fun stopwatch_accepts_a_zero_startOffset() = runTest {
        backgroundScope.stopwatch(startFormat = "SS", startOffset = Duration.ZERO)
    }

    @Test
    fun stopwatch_rejects_a_blank_startFormat() = runTest {
        assertFailsWith<InvalidTimeFormatException> {
            backgroundScope.stopwatch(startFormat = "")
        }
    }

    @Test
    fun stopwatch_changeFormatWhen_rejects_a_threshold_below_startOffset() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.stopwatch(startFormat = "SS", startOffset = 5.seconds) {
                changeFormatWhen(4.seconds, "MM:SS")
            }
        }
    }

    @Test
    fun stopwatch_changeFormatWhen_threshold_may_equal_startOffset() = runTest {
        backgroundScope.stopwatch(startFormat = "SS", startOffset = 5.seconds) {
            changeFormatWhen(5.seconds, "MM:SS")
        }
    }

    @Test
    fun stopwatch_actionWhen_rejects_a_threshold_equal_to_startOffset() = runTest {
        assertFailsWith<IllegalArgumentException> {
            backgroundScope.stopwatch(startFormat = "SS", startOffset = 5.seconds) {
                actionWhen(5.seconds) {}
            }
        }
    }

    @Test
    fun stopwatch_actionWhen_accepts_a_threshold_just_above_startOffset() = runTest {
        backgroundScope.stopwatch(startFormat = "SS", startOffset = 5.seconds) {
            actionWhen(5_001.milliseconds) {}
        }
    }

    @Test
    fun builder_function_is_reentrant_and_produces_independent_timers() = runTest {
        // A dummy fixed clock: this test cares about instance independence, not timing, and a
        // deterministic clock keeps it runnable on every target, including a plain Android unit
        // test JVM where the real platform SystemClock is unavailable without instrumentation.
        val clock = MonotonicClock { 0L }
        val timers = List(10) {
            backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        }
        timers.forEach { it.start() }
        // No shared mutable builder state: ten independent instances, ten independent states.
        kotlin.test.assertEquals(10, timers.map { it.state }.distinct().size)
    }
}
