package com.erkankrcr.corotimer.internal.format

import com.erkankrcr.corotimer.internal.format.Semantic.Companion.MILLIS_PER_HOUR
import com.erkankrcr.corotimer.internal.format.Semantic.Companion.MILLIS_PER_MINUTE
import com.erkankrcr.corotimer.internal.format.Semantic.Companion.MILLIS_PER_SECOND

/**
 * Renders a millisecond value through a parsed [Semantic].
 *
 * Two properties matter and are pinned by tests:
 *
 * 1. **The largest rendered unit absorbs everything above it.** `"MM:SS"` at 90 minutes renders
 *    `"90:00"`, not `"30:00"` — a pattern that omits hours is asking for total minutes.
 * 2. **Overflow widens, it never truncates.** `"HH"` at 100 hours renders `"100"`. Smaller units
 *    keep their declared width and stay zero-padded, so alignment is preserved everywhere that
 *    it can be.
 */
internal object SemanticFormatter {

    fun format(semantic: Semantic, millis: Long): String {
        val total = millis.coerceAtLeast(0L)
        val builder = StringBuilder(semantic.pattern.length + OVERFLOW_HEADROOM)

        for (segment in semantic.segments) {
            when (segment) {
                is PatternSegment.Literal -> builder.append(segment.text)
                is PatternSegment.Unit -> builder.append(renderUnit(semantic, segment, total))
            }
        }
        return builder.toString()
    }

    private fun renderUnit(semantic: Semantic, segment: PatternSegment.Unit, total: Long): String {
        val isLargest = segment.type == semantic.largestUnit
        return when (segment.type) {
            TimeUnitType.HOURS -> pad(total / MILLIS_PER_HOUR, segment.width)

            TimeUnitType.MINUTES -> {
                val value = if (isLargest) total / MILLIS_PER_MINUTE else (total / MILLIS_PER_MINUTE) % MINUTES_PER_HOUR
                pad(value, segment.width)
            }

            TimeUnitType.SECONDS -> {
                val value = if (isLargest) total / MILLIS_PER_SECOND else (total / MILLIS_PER_SECOND) % SECONDS_PER_MINUTE
                pad(value, segment.width)
            }

            TimeUnitType.SUB_SECONDS -> {
                val fraction = if (isLargest) total else total % MILLIS_PER_SECOND
                pad(fraction / subSecondDivisor(segment.width), segment.width)
            }
        }
    }

    /**
     * `L` renders tenths, `LL` hundredths, `LLL` whole milliseconds. A wider run cannot add
     * precision that milliseconds do not have, so it keeps millisecond resolution and is simply
     * padded out.
     */
    private fun subSecondDivisor(width: Int): Long = when (width) {
        1 -> 100L
        2 -> 10L
        else -> 1L
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private const val MINUTES_PER_HOUR = 60L
    private const val SECONDS_PER_MINUTE = 60L
    private const val OVERFLOW_HEADROOM = 8
}
