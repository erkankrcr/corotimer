package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.testutil.VirtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LifecycleTest {

    @Test
    fun double_start_is_a_noop() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        val first = timer.state.value
        timer.start()
        assertEquals(first, timer.state.value)
    }

    @Test
    fun double_pause_is_a_noop() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.pause()
        val first = timer.state.value
        timer.pause()
        assertEquals(first, timer.state.value)
    }

    @Test
    fun double_resume_is_a_noop() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.pause()
        timer.resume()
        val first = timer.state.value
        timer.resume()
        assertEquals(first, timer.state.value)
    }

    @Test
    fun double_release_is_a_noop() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.release()
        val first = timer.state.value
        timer.release()
        assertEquals(first, timer.state.value)
    }

    @Test
    fun every_method_is_a_noop_after_release() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.release()
        val frozen = timer.state.value

        timer.start()
        assertEquals(frozen, timer.state.value)
        timer.pause()
        assertEquals(frozen, timer.state.value)
        timer.resume()
        assertEquals(frozen, timer.state.value)
        timer.reset()
        assertEquals(frozen, timer.state.value)
        timer.setTime(1_000L)
        assertEquals(frozen, timer.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(frozen, timer.state.value)
    }

    @Test
    fun use_releases_the_timer_after_the_block_even_on_exception() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)

        runCatching {
            timer.use {
                it.start()
                error("boom")
            }
        }

        val frozen = timer.state.value
        timer.start()
        assertEquals(frozen, timer.state.value, "use{} must have released the timer even though the block threw")
    }

    @Test
    fun release_cancels_only_the_timers_own_child_job_not_the_parent_scope() = runTest {
        val clock = VirtualClock(testScheduler)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(coroutineContext + parentJob)

        val timer = parentScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.release()

        assertTrue(parentJob.isActive, "releasing a timer must not cancel the scope it was built with")
        parentJob.cancel()
    }

    /**
     * `pause(); state.value` must never require a coroutine dispatch in between — the entire
     * point of the compare-and-set reducer design over a mutex or `limitedParallelism(1)`
     * dispatcher, both of which would make this assertion flaky.
     */
    @Test
    fun state_reflects_a_transition_synchronously_with_no_dispatcher_pump_required() = runTest {
        val clock = VirtualClock(testScheduler)
        val timer = backgroundScope.countdownTimer(startTime = 5.seconds, startFormat = "SS", clock = clock)
        timer.start()
        timer.pause()
        assertIs<TimeState.Paused>(timer.state.value)
    }
}
