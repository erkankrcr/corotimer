package com.erkankrcr.corotimer.internal.format

/** The four time units a pattern can render, ordered from largest to smallest. */
internal enum class TimeUnitType(val symbol: Char) {
    HOURS('H'),
    MINUTES('M'),
    SECONDS('S'),
    SUB_SECONDS('L'),
    ;

    companion object {
        val SYMBOLS: Set<Char> = entries.mapTo(mutableSetOf()) { it.symbol }

        fun fromSymbol(symbol: Char): TimeUnitType? = entries.firstOrNull { it.symbol == symbol }
    }
}
