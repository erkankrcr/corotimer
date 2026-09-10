package com.erkankrcr.corotimer.internal.format

/**
 * A parsed pattern is a flat list of segments rendered left to right.
 *
 * Modelling the pattern as segments — rather than as character positions into a fixed-width
 * buffer — is what lets the largest unit widen on overflow instead of silently dropping its
 * most significant digits, and it removes a whole class of index-arithmetic mistakes when a
 * pattern contains escape characters.
 */
internal sealed interface PatternSegment {

    /** Text copied verbatim, including characters that were escaped with `#`. */
    data class Literal(val text: String) : PatternSegment

    /** A live time unit occupying [width] characters in the pattern. */
    data class Unit(val type: TimeUnitType, val width: Int) : PatternSegment
}
