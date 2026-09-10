package com.erkankrcr.corotimer

import android.os.SystemClock

/**
 * `SystemClock.elapsedRealtime()`: unlike `System.nanoTime()`, this keeps counting through deep
 * sleep, which is the entire reason [MonotonicClock] is an injectable interface instead of a
 * direct call to [kotlin.time.TimeSource.Monotonic] — a background countdown must not lose the
 * time the device spent asleep.
 */
internal actual fun createSystemClock(): MonotonicClock = MonotonicClock {
    SystemClock.elapsedRealtime()
}
