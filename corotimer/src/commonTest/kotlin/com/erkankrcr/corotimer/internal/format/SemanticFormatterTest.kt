package com.erkankrcr.corotimer.internal.format

import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticFormatterTest {

    private fun format(pattern: String, millis: Long): String = SemanticFormatter.format(PatternAnalyzer.analyze(pattern), millis)

    // ---------------------------------------------------------------- basic rendering

    @Test
    fun zero_renders_as_zero_everywhere() {
        assertEquals("00:00", format("MM:SS", 0))
        assertEquals("00:00:00", format("HH:MM:SS", 0))
    }

    @Test
    fun one_millisecond() {
        assertEquals("00:00.001", format("MM:SS.LLL", 1))
    }

    @Test
    fun `999_milliseconds_does_not_round_up_to_a_second`() {
        assertEquals("00.999", format("SS.LLL", 999))
    }

    @Test
    fun exactly_one_second() {
        assertEquals("00:01", format("MM:SS", 1_000))
    }

    @Test
    fun `59_999_milliseconds_stays_in_the_current_second`() {
        assertEquals("00:59.999", format("MM:SS.LLL", 59_999))
    }

    @Test
    fun exactly_one_hour() {
        assertEquals("01:00:00", format("HH:MM:SS", 3_600_000))
    }

    @Test
    fun ninety_minutes_with_no_hour_placeholder_shows_total_minutes() {
        assertEquals("90:00", format("MM:SS", 90 * 60_000L))
    }

    @Test
    fun ninety_minutes_with_hour_placeholder_wraps_normally() {
        assertEquals("01:30:00", format("HH:MM:SS", 90 * 60_000L))
    }

    // ---------------------------------------------------------------- overflow widens, never truncates

    @Test
    fun hours_overflow_widens_the_field_instead_of_truncating() {
        assertEquals("100", format("HH", 100 * 3_600_000L))
    }

    @Test
    fun two_digit_hours_field_still_widens_past_99() {
        assertEquals("100:00", format("HH:MM", 100 * 3_600_000L))
    }

    @Test
    fun smaller_units_keep_their_declared_width_even_when_the_largest_unit_overflows() {
        assertEquals("100:05", format("HH:MM", 100 * 3_600_000L + 5 * 60_000L))
    }

    @Test
    fun does_not_overflow_or_crash_at_long_max_value() {
        // Must not throw; the exact digit count is unimportant, only that it renders.
        val rendered = format("HH", Long.MAX_VALUE)
        assert(rendered.isNotEmpty())
    }

    // ---------------------------------------------------------------- negative input clamps to zero

    @Test
    fun negative_input_clamps_to_zero_rather_than_a_minus_sign() {
        assertEquals("00:00", format("MM:SS", -5_000))
    }

    // ---------------------------------------------------------------- sub-second scaling

    @Test
    fun single_L_renders_tenths() {
        assertEquals("4", format("L", 450))
    }

    @Test
    fun double_L_renders_hundredths() {
        assertEquals("45", format("LL", 450))
    }

    @Test
    fun triple_L_renders_milliseconds() {
        assertEquals("450", format("LLL", 450))
    }

    @Test
    fun quadruple_L_still_caps_at_millisecond_resolution() {
        assertEquals("0450", format("LLLL", 450))
    }

    // ---------------------------------------------------------------- literals & escaping in output

    @Test
    fun literal_text_is_copied_verbatim() {
        assertEquals("T-05!", format("T-SS!", 5_000))
    }

    @Test
    fun repeated_symbol_run_after_the_first_is_rendered_as_literal_text() {
        assertEquals("00:30:HH", format("HH:MM:HH", 30 * 60_000L))
    }
}
