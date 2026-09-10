package com.erkankrcr.corotimer.internal.format

import com.erkankrcr.corotimer.InvalidTimeFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatternAnalyzerTest {

    private fun widthsOf(pattern: String) = PatternAnalyzer.analyze(pattern).widths.mapKeys { it.key.symbol }

    @Test
    fun single_symbol_widths() {
        assertEquals(mapOf('S' to 1), widthsOf("S"))
        assertEquals(mapOf('S' to 2), widthsOf("SS"))
        assertEquals(mapOf('S' to 3), widthsOf("SSS"))
    }

    @Test
    fun full_combination_with_literals() {
        val semantic = PatternAnalyzer.analyze("HH:MM:SS.LLL")
        assertEquals(mapOf('H' to 2, 'M' to 2, 'S' to 2, 'L' to 3), semantic.widths.mapKeys { it.key.symbol })
        assertEquals(TimeUnitType.HOURS, semantic.largestUnit)
        assertEquals(TimeUnitType.SUB_SECONDS, semantic.smallestUnit)
    }

    @Test
    fun escape_renders_the_next_character_literally() {
        val semantic = PatternAnalyzer.analyze("#Hh SS")
        assertEquals(mapOf('S' to 2), semantic.widths.mapKeys { it.key.symbol })
        val text = semantic.segments.filterIsInstance<PatternSegment.Literal>().joinToString("") { it.text }
        assertTrue(text.contains("Hh"))
    }

    @Test
    fun double_hash_renders_a_literal_hash() {
        val semantic = PatternAnalyzer.analyze("SS##")
        val literal = semantic.segments.filterIsInstance<PatternSegment.Literal>().single()
        assertEquals("#", literal.text)
    }

    @Test
    fun trailing_escape_character_with_nothing_to_escape_is_a_literal_hash() {
        // No character follows the final '#', so it cannot escape anything and falls through to
        // being treated as ordinary literal text instead.
        val semantic = PatternAnalyzer.analyze("SS#")
        val literal = semantic.segments.filterIsInstance<PatternSegment.Literal>().single()
        assertEquals("#", literal.text)
    }

    @Test
    fun second_run_of_a_symbol_is_literal_text_not_a_placeholder() {
        val semantic = PatternAnalyzer.analyze("HH:MM:HH")
        assertEquals(mapOf('H' to 2, 'M' to 2), semantic.widths.mapKeys { it.key.symbol })
        val units = semantic.segments.filterIsInstance<PatternSegment.Unit>()
        assertEquals(2, units.size) // only the first H-run and the M-run are live units
    }

    @Test
    fun non_contiguous_repeats_of_the_same_unit_still_collapse_to_one_placeholder() {
        val semantic = PatternAnalyzer.analyze("HH:MM:HH:MM")
        assertEquals(2, semantic.segments.filterIsInstance<PatternSegment.Unit>().size)
    }

    @Test
    fun pattern_made_entirely_of_escaped_symbols_is_rejected() {
        // "#S#S" escapes every symbol into literal text "SS" with no live unit left to render.
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("#S#S") }
    }

    @Test
    fun escaped_symbol_next_to_a_live_one_only_the_live_one_is_a_unit() {
        val semantic = PatternAnalyzer.analyze("#SSS")
        assertEquals(mapOf('S' to 2), semantic.widths.mapKeys { it.key.symbol })
    }

    @Test
    fun blank_pattern_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("") }
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("   ") }
    }

    @Test
    fun fully_escaped_pattern_with_no_symbols_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("###") }
    }

    @Test
    fun all_symbols_escaped_leaves_no_live_unit_and_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("#H#M#S") }
    }

    @Test
    fun exception_carries_the_offending_pattern() {
        val exception = assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("") }
        assertEquals("", exception.pattern)
        assertTrue(exception.message!!.contains("pattern"))
    }

    // ---------------------------------------------------------------- combination validation

    @Test
    fun hours_without_minutes_but_with_seconds_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("HH:SS") }
    }

    @Test
    fun hours_without_minutes_but_with_subseconds_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("HH.LL") }
    }

    @Test
    fun hours_minutes_and_subseconds_without_seconds_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("HH:MM.LL") }
    }

    @Test
    fun minutes_and_subseconds_without_seconds_is_rejected() {
        assertFailsWith<InvalidTimeFormatException> { PatternAnalyzer.analyze("MM.LL") }
    }

    @Test
    fun hours_alone_is_valid() {
        PatternAnalyzer.analyze("HH")
    }

    @Test
    fun hours_and_minutes_without_seconds_is_valid() {
        PatternAnalyzer.analyze("HH:MM")
    }

    @Test
    fun minutes_and_seconds_without_hours_is_valid() {
        PatternAnalyzer.analyze("MM:SS")
    }

    @Test
    fun seconds_and_subseconds_without_minutes_or_hours_is_valid() {
        PatternAnalyzer.analyze("S.LLL")
    }

    @Test
    fun full_four_unit_pattern_is_valid() {
        PatternAnalyzer.analyze("HH:MM:SS.LLL")
    }

    @Test
    fun subseconds_alone_is_valid() {
        PatternAnalyzer.analyze("LLL")
    }

    @Test
    fun order_of_symbols_in_the_pattern_does_not_affect_validity() {
        // Unusual, but every unit is still present, so the combination rule is satisfied.
        PatternAnalyzer.analyze("SS:MM:HH")
    }

    @Test
    fun literal_only_prefix_and_suffix_are_preserved() {
        val semantic = PatternAnalyzer.analyze("T-SS!")
        val literals = semantic.segments.filterIsInstance<PatternSegment.Literal>().map { it.text }
        assertEquals(listOf("T-", "!"), literals)
    }
}
