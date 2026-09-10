package com.erkankrcr.corotimer.internal.format

import com.erkankrcr.corotimer.InvalidTimeFormatException

/**
 * Parses a format pattern into a [Semantic].
 *
 * Escaping rule: `#` removes the special meaning of the character that follows it, and the `#`
 * itself is dropped from the output. `##` therefore renders a literal `#`. This differs from a
 * lookbehind-based rule in one important way — it is applied in a single left-to-right pass that
 * builds the output text and the segment list together, so a pattern such as `"#HMM:SS"` cannot
 * end up with unit offsets that point into the wrong characters.
 *
 * Only the **first** run of each symbol is a live unit; a later run of the same symbol is copied
 * verbatim, so `"HH:MM:HH"` renders hours, minutes, then the literal text `HH`.
 */
internal object PatternAnalyzer {

    private const val ESCAPE_CHAR = '#'

    fun analyze(pattern: String): Semantic {
        val segments = mutableListOf<PatternSegment>()
        val widths = linkedMapOf<TimeUnitType, Int>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                segments += PatternSegment.Literal(literal.toString())
                literal.clear()
            }
        }

        var i = 0
        while (i < pattern.length) {
            val char = pattern[i]
            val unit = TimeUnitType.fromSymbol(char)

            when {
                char == ESCAPE_CHAR && i + 1 < pattern.length -> {
                    literal.append(pattern[i + 1])
                    i += 2
                }
                unit == null -> {
                    literal.append(char)
                    i++
                }
                else -> {
                    var runEnd = i
                    while (runEnd + 1 < pattern.length && pattern[runEnd + 1] == char) runEnd++
                    val runLength = runEnd - i + 1

                    if (unit in widths) {
                        // A repeated unit is text, not a second placeholder.
                        literal.append(pattern, i, runEnd + 1)
                    } else {
                        flushLiteral()
                        widths[unit] = runLength
                        segments += PatternSegment.Unit(unit, runLength)
                    }
                    i = runEnd + 1
                }
            }
        }
        flushLiteral()

        if (widths.isEmpty()) {
            throw InvalidTimeFormatException(
                "Format must contain at least one unescaped time symbol (H, M, S or L)",
                pattern,
            )
        }
        validateCombination(widths.keys, pattern)

        val present = TimeUnitType.entries.filter { it in widths }
        return Semantic(
            pattern = pattern,
            segments = segments,
            widths = widths,
            smallestUnit = present.last(),
            largestUnit = present.first(),
        )
    }

    /**
     * Rejects patterns that would render a discontinuous set of units, where the omitted middle
     * unit makes the output ambiguous (`"HH:SS"` cannot show 90 minutes without lying).
     */
    private fun validateCombination(units: Set<TimeUnitType>, pattern: String) {
        val hasHours = TimeUnitType.HOURS in units
        val hasMinutes = TimeUnitType.MINUTES in units
        val hasSeconds = TimeUnitType.SECONDS in units
        val hasSubSeconds = TimeUnitType.SUB_SECONDS in units

        val error = when {
            hasHours && !hasMinutes && (hasSeconds || hasSubSeconds) ->
                "H requires M when S or L are also present"
            hasHours && hasMinutes && !hasSeconds && hasSubSeconds ->
                "S is required when H, M and L are all present"
            !hasHours && hasMinutes && !hasSeconds && hasSubSeconds ->
                "S is required when both M and L are present"
            else -> null
        }
        if (error != null) throw InvalidTimeFormatException(error, pattern)
    }
}
