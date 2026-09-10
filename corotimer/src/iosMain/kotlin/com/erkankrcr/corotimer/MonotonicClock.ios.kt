package com.erkankrcr.corotimer

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.mach_continuous_time
import platform.darwin.mach_timebase_info
import platform.darwin.mach_timebase_info_data_t

/**
 * `mach_continuous_time()`, deliberately not `mach_absolute_time()` or `NSProcessInfo.systemUptime`.
 *
 * `mach_absolute_time` stops across sleep on iOS, same failure mode as `System.nanoTime()` on
 * Android. `NSProcessInfo.systemUptime` — used by the reference implementation this library
 * replaces — actively *excludes* time asleep, which is the opposite bug: it under-counts a
 * background countdown by however long the device was asleep. `mach_continuous_time` is the one
 * clock that keeps advancing through sleep, matching [SystemClock.elapsedRealtime] on Android and
 * upholding the single sleep-inclusive contract [MonotonicClock] documents.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun createSystemClock(): MonotonicClock {
    val millisPerTick = memScoped {
        val info = alloc<mach_timebase_info_data_t>()
        mach_timebase_info(info.ptr)
        info.numer.toDouble() / info.denom.toDouble() / 1_000_000.0
    }
    return MonotonicClock {
        (mach_continuous_time().toDouble() * millisPerTick).toLong()
    }
}
