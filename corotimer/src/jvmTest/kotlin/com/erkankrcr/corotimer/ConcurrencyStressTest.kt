package com.erkankrcr.corotimer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The compare-and-set reducer design exists specifically so that concurrent calls from multiple
 * real OS threads can never corrupt state or throw — this is the one property that cannot be
 * verified under `runTest`'s single-threaded virtual dispatcher, so it gets real threads instead.
 */
class ConcurrencyStressTest {

    @Test
    fun eight_threads_hammering_every_method_never_throws_and_leaves_a_consistent_state() {
        val pool = Executors.newFixedThreadPool(8)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val timer = scope.countdownTimer(startTime = 5.seconds, startFormat = "SS")
        val uncaught = AtomicInteger(0)
        val iterations = 5_000
        val latch = CountDownLatch(8)

        repeat(8) {
            pool.submit {
                try {
                    repeat(iterations) { i ->
                        when (i % 6) {
                            0 -> timer.start()
                            1 -> timer.pause()
                            2 -> timer.resume()
                            3 -> timer.reset()
                            4 -> timer.setTime((i % 4_000).toLong())
                            else -> timer.state.value
                        }
                    }
                } catch (t: Throwable) {
                    uncaught.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()

        assertEquals(0, uncaught.get(), "no method call may throw under concurrent access")
        val state = timer.state.value
        // Internally consistent: both currentMillis and formattedTime come from the same atomic
        // snapshot (never a torn read across two separate fields), and every phase renders a sane
        // in-bounds value. (Not compared by re-formatting currentMillis: a countdown's
        // formattedTime is deliberately ceiling-rounded relative to the raw currentMillis it also
        // exposes — see Reducer.render — so the two are not expected to round-trip through the
        // formatter for a countdown the way they would for a stopwatch.)
        assertTrue(state.currentMillis in 0..5_000L, "currentMillis out of configured bounds: $state")
        assertTrue(state.formattedTime.isNotEmpty())

        timer.release()
        scope.cancel()
    }

    @Test
    fun a_thousand_concurrent_resets_race_the_live_tick_loop_without_corrupting_state() {
        val pool = Executors.newFixedThreadPool(8)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val timer = scope.countdownTimer(startTime = 5.seconds, startFormat = "SS")
        timer.start()
        val uncaught = AtomicInteger(0)
        val latch = CountDownLatch(8)

        repeat(8) {
            pool.submit {
                try {
                    repeat(125) { timer.reset(); timer.start() }
                } catch (t: Throwable) {
                    uncaught.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()

        assertEquals(0, uncaught.get())
        timer.release()
        scope.cancel()
    }

    @Test
    fun release_from_multiple_threads_never_invokes_an_uncaught_exception_handler() {
        val exceptions = AtomicInteger(0)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> exceptions.incrementAndGet() }
        try {
            val pool = Executors.newFixedThreadPool(8)
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val timer = scope.countdownTimer(startTime = 5.seconds, startFormat = "SS")
            timer.start()
            val latch = CountDownLatch(8)

            repeat(8) {
                pool.submit {
                    try {
                        timer.release()
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
            runBlocking { scope.coroutineContext[Job]?.children?.forEach { it.join() } }

            assertEquals(0, exceptions.get())
            scope.cancel()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}
