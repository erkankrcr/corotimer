package com.erkankrcr.corotimer.internal.format

/**
 * The immutable, fully parsed form of a format pattern.
 *
 * @property pattern the original pattern, kept for error messages and [toString].
 * @property segments the pattern flattened into render order.
 * @property widths width in characters of each live unit; a unit absent from this map is not
 *   rendered by the pattern.
 * @property smallestUnit the finest unit the pattern renders — this drives the tick granularity.
 * @property largestUnit the coarsest unit the pattern renders — this unit absorbs all time above
 *   it and is the only one allowed to widen past its declared width.
 */
internal class Semantic(
    val pattern: String,
    val segments: List<PatternSegment>,
    val widths: Map<TimeUnitType, Int>,
    val smallestUnit: TimeUnitType,
    val largestUnit: TimeUnitType,
) {
    fun has(unit: TimeUnitType): Boolean = unit in widths

    fun widthOf(unit: TimeUnitType): Int = widths[unit] ?: 0

    /**
     * Milliseconds between two consecutive values the pattern can actually distinguish.
     *
     * A tick more often than this cannot change the rendered string, so this is the natural
     * wake-up period for [com.erkankrcr.corotimer.TickPolicy.Aligned].
     */
    val granularityMillis: Long
        get() = when (smallestUnit) {
            TimeUnitType.HOURS -> MILLIS_PER_HOUR
            TimeUnitType.MINUTES -> MILLIS_PER_MINUTE
            TimeUnitType.SECONDS -> MILLIS_PER_SECOND
            TimeUnitType.SUB_SECONDS -> when (widthOf(TimeUnitType.SUB_SECONDS)) {
                1 -> 100L
                2 -> 10L
                else -> 1L
            }
        }

    override fun toString(): String = "Semantic($pattern)"

    companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
        const val MILLIS_PER_MINUTE: Long = 60_000L
        const val MILLIS_PER_HOUR: Long = 3_600_000L
    }
}
