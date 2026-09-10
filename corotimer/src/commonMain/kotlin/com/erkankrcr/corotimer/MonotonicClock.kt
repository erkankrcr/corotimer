package com.erkankrcr.corotimer

/**
 * The time source a timer measures with.
 *
 * ### Contract
 *
 * [elapsedRealtimeMillis] must be **monotonically non-decreasing** and must **keep counting while
 * the device is asleep**. Only differences between two readings are meaningful; the absolute
 * origin is unspecified and may differ per platform and per process.
 *
 * The sleep-inclusive requirement is the entire reason this interface exists instead of
 * [kotlin.time.TimeSource.Monotonic]. On Android that maps to `System.nanoTime()`, which stops
 * advancing in deep sleep — a 30-minute background countdown would under-count by however long
 * the device slept. Every platform implementation here uses a sleep-inclusive clock so the
 * library behaves identically everywhere.
 *
 * Implementations must be safe to call from any thread.
 */
public fun interface MonotonicClock {

    /** Milliseconds since an unspecified fixed origin, including time spent asleep. */
    public fun elapsedRealtimeMillis(): Long

    public companion object {

        /** The platform clock. Android: `SystemClock.elapsedRealtime()`. */
        public val System: MonotonicClock by lazy(LazyThreadSafetyMode.PUBLICATION) {
            createSystemClock()
        }
    }
}

internal expect fun createSystemClock(): MonotonicClock
