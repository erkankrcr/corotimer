package com.erkankrcr.corotimer

/**
 * `System.nanoTime()` on the JVM. Unlike `Thread.sleep` wall-clock alternatives, it is immune to
 * system time adjustments, and a desktop or server JVM process has no deep-sleep suspend state to
 * lose time across — the sleep-inclusive requirement documented on [MonotonicClock] is a concern
 * specific to mobile platforms.
 */
internal actual fun createSystemClock(): MonotonicClock = MonotonicClock {
    System.nanoTime() / 1_000_000L
}
