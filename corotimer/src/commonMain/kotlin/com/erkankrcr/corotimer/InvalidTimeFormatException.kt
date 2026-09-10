package com.erkankrcr.corotimer

/**
 * Thrown when a format pattern cannot be parsed.
 *
 * This is argument validation, so it extends [IllegalArgumentException] and is unchecked. The
 * offending [pattern] is carried on the exception because the message alone is rarely enough to
 * find the call site when patterns are built dynamically.
 *
 * @property pattern the pattern that failed to parse.
 */
public class InvalidTimeFormatException internal constructor(
    message: String,
    public val pattern: String,
) : IllegalArgumentException("$message (pattern: \"$pattern\")")
