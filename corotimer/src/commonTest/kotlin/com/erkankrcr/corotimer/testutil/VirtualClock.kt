package com.erkankrcr.corotimer.testutil

import com.erkankrcr.corotimer.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A [MonotonicClock] backed directly by a [TestCoroutineScheduler]'s virtual time.
 *
 * This is what makes `advanceTimeBy` and the engine's `delay()` calls move in lockstep: the clock
 * a test reads and the clock the tick loop sleeps against are the exact same virtual timeline, so
 * driving the scheduler forward *is* driving the timer forward — no manual `tick()` stepping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VirtualClock(private val scheduler: TestCoroutineScheduler) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = scheduler.currentTime
}
