package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.internal.format.PatternAnalyzer
import com.erkankrcr.corotimer.internal.format.Semantic
import com.erkankrcr.corotimer.internal.format.SemanticFormatter

/**
 * Renders millisecond values with a corotimer format pattern, independently of any timer.
 *
 * ### Pattern syntax
 *
 * | Symbol | Meaning |
 * |---|---|
 * | `H` | hours |
 * | `M` | minutes |
 * | `S` | seconds |
 * | `L` | sub-seconds — `L` tenths, `LL` hundredths, `LLL` milliseconds |
 * | `#` | escape: renders the next character literally; `##` renders a literal `#` |
 *
 * Repeat a symbol to set its width: `"MM:SS"` zero-pads both to two digits. Only the first run of
 * each symbol is a placeholder, so `"HH:MM:HH"` renders hours, minutes, then the literal `HH`.
 *
 * Two behaviours are worth knowing:
 *
 * - **The largest unit in the pattern absorbs everything above it.** `"MM:SS"` at 90 minutes is
 *   `"90:00"`, not `"30:00"`.
 * - **Overflow widens rather than truncating.** `"HH"` at 100 hours is `"100"`.
 *
 * Instances are immutable and safe to share between threads.
 *
 * ```kotlin
 * val formatter = TimeFormatter.parse("MM:SS.LL")
 * formatter.format(83_450)          // "01:23.45"
 * TimeFormatter.format("SS", 5_000) // "05"
 * ```
 */
public class TimeFormatter private constructor(
    internal val semantic: Semantic,
) {
    /** The pattern this formatter was parsed from. */
    public val pattern: String get() = semantic.pattern

    /**
     * Renders [millis]. Negative input is clamped to zero rather than producing a `-` sign that
     * no pattern reserves room for.
     */
    public fun format(millis: Long): String = SemanticFormatter.format(semantic, millis)

    override fun toString(): String = "TimeFormatter($pattern)"

    override fun equals(other: Any?): Boolean = other is TimeFormatter && other.pattern == pattern

    override fun hashCode(): Int = pattern.hashCode()

    public companion object {

        /**
         * Parses [pattern] into a reusable formatter.
         *
         * @throws InvalidTimeFormatException if the pattern contains no unescaped time symbol, or
         *   omits a unit that its other units require (for example `"HH:SS"`).
         */
        public fun parse(pattern: String): TimeFormatter = TimeFormatter(PatternAnalyzer.analyze(pattern))

        /**
         * Parses [pattern] and renders [millis] in one call.
         *
         * The pattern is parsed on every call — deliberately, since a shared cache would need
         * synchronisation on every platform and parsing a short pattern is a single linear scan.
         * Hold on to a [parse] result instead if you format in a loop.
         *
         * @throws InvalidTimeFormatException if [pattern] cannot be parsed.
         */
        public fun format(pattern: String, millis: Long): String = parse(pattern).format(millis)
    }
}
