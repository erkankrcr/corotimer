package com.erkankrcr.corotimer

/**
 * A single lap recorded by [Stopwatch.lap].
 *
 * @property number one-based position in [Stopwatch.laps].
 * @property lapMillis time since the previous lap, or since the stopwatch started for the first
 *   lap. A configured [StopwatchBuilder.startOffset] is excluded, so the first lap measures real
 *   elapsed time rather than the offset.
 * @property splitMillis total displayed time at the moment the lap was captured, offset included.
 * @property formattedLap [lapMillis] rendered with the format active at capture time.
 * @property formattedSplit [splitMillis] rendered with the format active at capture time.
 */
public data class Lap(
    public val number: Int,
    public val lapMillis: Long,
    public val splitMillis: Long,
    public val formattedLap: String,
    public val formattedSplit: String,
)
