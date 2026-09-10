package com.erkankrcr.corotimer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Touches every public member using only public imports. This is a compiler-enforced guarantee as
 * much as a runtime one: `explicitApi()` fails the build if an `internal` type ever leaks into a
 * public signature, so the fact that this file compiles at all is itself part of the check —
 * exactly the class of accident the reference implementation's `internal`-package `TimeSource`
 * shows can otherwise slip through unnoticed.
 */
class PublicSurfaceSmokeTest {

    @Test
    fun countdownTimer_full_surface() = runTest {
        val scope: CoroutineScope = this
        val timer: CountdownTimer = scope.countdownTimer(
            startTime = 5.seconds,
            startFormat = "MM:SS",
            clock = MonotonicClock.System,
            tickPolicy = TickPolicy.Aligned,
            onError = { _: Throwable -> },
        ) {
            changeFormatWhen(3.seconds, "SS.LL")
            actionWhen(1.seconds) {}
            onFinish {}
            onCycleComplete { _: Int -> }
            repeat(2)
        }

        val state: TimeState = timer.state.value
        assertIs<TimeState.Idle>(state)
        assertEquals(false, state.isRunning)
        assertEquals(false, state.isFinished)
        assertEquals(false, state.isActive)

        timer.start()
        timer.pause()
        timer.resume()
        timer.setTime(1_000L)
        timer.setTime(2.seconds)
        timer.reset()
        timer.release()
    }

    @Test
    fun stopwatch_full_surface() = runTest {
        val watch: Stopwatch = this.stopwatch(
            startFormat = "MM:SS",
            startOffset = 0.seconds,
            clock = MonotonicClock.System,
            tickPolicy = TickPolicy.Fixed(250.milliseconds),
        ) {
            changeFormatWhen(1.seconds, "SS")
            actionWhen(1.seconds) {}
        }

        watch.start()
        val lap: Lap? = watch.lap()
        val laps: List<Lap> = watch.laps
        laps.forEach { l: Lap ->
            val number: Int = l.number
            val lapMillis: Long = l.lapMillis
            val splitMillis: Long = l.splitMillis
            val formattedLap: String = l.formattedLap
            val formattedSplit: String = l.formattedSplit
            assertEquals(number >= 1, true)
            assertEquals(lapMillis >= 0, true)
            assertEquals(splitMillis >= 0, true)
            assertEquals(formattedLap.isNotEmpty(), true)
            assertEquals(formattedSplit.isNotEmpty(), true)
        }
        watch.use { it.pause() }
        assertEquals(lap == null || lap.number >= 1, true)
    }

    @Test
    fun timeFormatter_and_exception_surface() {
        val formatter: TimeFormatter = TimeFormatter.parse("MM:SS")
        val rendered: String = formatter.format(1_000L)
        assertEquals("00:01", rendered)
        assertEquals("00:01", TimeFormatter.format("MM:SS", 1_000L))
        assertEquals("MM:SS", formatter.pattern)

        val exception: InvalidTimeFormatException = try {
            TimeFormatter.parse("HH:SS")
            error("unreachable")
        } catch (e: InvalidTimeFormatException) {
            e
        }
        val pattern: String = exception.pattern
        assertEquals("HH:SS", pattern)
    }

    @Test
    fun monotonicClock_surface() {
        val clock: MonotonicClock = MonotonicClock { 42L }
        val value: Long = clock.elapsedRealtimeMillis()
        assertEquals(42L, value)
        val system: MonotonicClock = MonotonicClock.System
        assertEquals(true, system.elapsedRealtimeMillis() >= 0L)
    }

    @Test
    fun timeState_sealed_hierarchy_is_exhaustive_over_public_variants() {
        val states: List<TimeState> = listOf(
            TimeState.Idle(0L, "00", 0),
            TimeState.Running(0L, "00", 0),
            TimeState.Paused(0L, "00", 0),
            TimeState.Finished("00", 0),
        )
        for (state in states) {
            val description = when (state) {
                is TimeState.Idle -> "idle"
                is TimeState.Running -> "running"
                is TimeState.Paused -> "paused"
                is TimeState.Finished -> "finished"
            }
            assertEquals(true, description.isNotEmpty())
        }
    }
}
