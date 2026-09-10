package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LapTest {

    @Test
    fun laps_are_numbered_from_one() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()

        advanceTimeBy(1_000)
        runCurrent()
        watch.lap()
        advanceTimeBy(1_000)
        runCurrent()
        watch.lap()

        assertEquals(listOf(1, 2), watch.laps.map { it.number })
    }

    @Test
    fun lap_split_is_the_delta_from_the_previous_lap() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()

        advanceTimeBy(1_000)
        runCurrent()
        val first = watch.lap()!!
        assertEquals(1_000L, first.lapMillis)
        assertEquals(1_000L, first.splitMillis)

        advanceTimeBy(1_500)
        runCurrent()
        val second = watch.lap()!!
        assertEquals(1_500L, second.lapMillis)
        assertEquals(2_500L, second.splitMillis)
    }

    /** A configured startOffset is excluded from the *first* lap's delta, but included in the split. */
    @Test
    fun startOffset_is_excluded_from_the_first_laps_delta_but_included_in_its_split() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "MM:SS", startOffset = 1.minutes, clock = clock)
        watch.start()

        advanceTimeBy(10_000)
        runCurrent()
        val first = watch.lap()!!
        assertEquals(10_000L, first.lapMillis, "the offset itself must not count as elapsed lap time")
        assertEquals(70_000L, first.splitMillis, "the split is the full displayed time, offset included")
    }

    @Test
    fun lap_uses_the_currently_active_format() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock) {
            changeFormatWhen(2.seconds, "MM:SS")
        }
        watch.start()

        advanceTimeBy(3_000)
        runCurrent()
        val lap = watch.lap()!!
        assertEquals("00:03", lap.formattedSplit)
    }

    @Test
    fun reset_clears_recorded_laps() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()
        advanceTimeBy(1_000)
        runCurrent()
        watch.lap()
        assertEquals(1, watch.laps.size)

        watch.reset()
        assertEquals(0, watch.laps.size)
    }

    /** Bug 8: lapping a paused stopwatch must be allowed and must return a real value. */
    @Test
    fun lap_while_paused_is_allowed_and_returns_a_non_null_value() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()
        advanceTimeBy(1_500)
        runCurrent()
        watch.pause()

        val lap = watch.lap()
        assertEquals(1_500L, lap?.splitMillis)
    }

    @Test
    fun lap_on_an_idle_stopwatch_returns_null() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        assertNull(watch.lap())
    }

    @Test
    fun lap_after_release_returns_null() = runTest {
        val clock = VirtualClock(testScheduler)
        val watch = backgroundScope.stopwatch(startFormat = "SS", clock = clock)
        watch.start()
        advanceTimeBy(1_000)
        runCurrent()
        watch.release()
        assertNull(watch.lap())
    }
}
