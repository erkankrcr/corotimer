package com.erkankrcr.corotimer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeFormatterTest {

    @Test
    fun parse_and_format_round_trip() {
        val formatter = TimeFormatter.parse("MM:SS.LL")
        assertEquals("01:23.45", formatter.format(83_450))
        assertEquals("MM:SS.LL", formatter.pattern)
    }

    @Test
    fun static_format_parses_and_renders_in_one_call() {
        assertEquals("05", TimeFormatter.format("SS", 5_000))
    }

    @Test
    fun invalid_pattern_throws_with_the_pattern_attached() {
        val exception = assertFailsWith<InvalidTimeFormatException> { TimeFormatter.parse("HH:SS") }
        assertEquals("HH:SS", exception.pattern)
    }

    @Test
    fun two_formatters_from_the_same_pattern_are_equal() {
        assertEquals(TimeFormatter.parse("MM:SS"), TimeFormatter.parse("MM:SS"))
        assertEquals(TimeFormatter.parse("MM:SS").hashCode(), TimeFormatter.parse("MM:SS").hashCode())
    }

    @Test
    fun formatters_from_different_patterns_are_not_equal() {
        assertTrue(TimeFormatter.parse("MM:SS") != TimeFormatter.parse("SS"))
    }

    @Test
    fun toString_reports_the_pattern() {
        assertTrue(TimeFormatter.parse("MM:SS").toString().contains("MM:SS"))
    }

    @Test
    fun negative_millis_render_as_zero() {
        assertEquals("00:00", TimeFormatter.format("MM:SS", -1))
    }
}
