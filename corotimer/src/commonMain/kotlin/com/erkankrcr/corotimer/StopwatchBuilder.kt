package com.erkankrcr.corotimer

import com.erkankrcr.corotimer.internal.engine.ActionEntry
import com.erkankrcr.corotimer.internal.engine.Engine
import com.erkankrcr.corotimer.internal.engine.EngineConfig
import com.erkankrcr.corotimer.internal.engine.EngineState
import com.erkankrcr.corotimer.internal.engine.Phase
import com.erkankrcr.corotimer.internal.engine.Reducer
import com.erkankrcr.corotimer.internal.engine.SemanticEntry
import com.erkankrcr.corotimer.internal.format.PatternAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * Configures a [Stopwatch] built by [stopwatch].
 *
 * Instances are created fresh by [stopwatch] on every call — there is no shared, mutable builder
 * state to accidentally reuse across stopwatches.
 */
public class StopwatchBuilder internal constructor(private val startOffsetMillis: Long) {

    private val formatEntries = mutableListOf<SemanticEntry>()
    private val actionEntries = mutableListOf<ActionEntry>()

    /**
     * Switches the rendered format once the elapsed time reaches or passes [threshold].
     *
     * Level-triggered, not edge-triggered: the format in force is always whichever configured
     * threshold most tightly bounds the current elapsed time.
     *
     * @throws IllegalArgumentException if [threshold] is negative or less than the stopwatch's
     *   start offset, or if [format] cannot be parsed.
     */
    public fun changeFormatWhen(threshold: Duration, format: String) {
        val millis = threshold.inWholeMilliseconds
        require(millis in startOffsetMillis..Long.MAX_VALUE) {
            "changeFormatWhen threshold must be at least the start offset ($startOffsetMillis ms), was $millis"
        }
        formatEntries += SemanticEntry(millis, PatternAnalyzer.analyze(format))
    }

    /**
     * Runs [action] exactly once when the elapsed time passes [threshold].
     *
     * Edge-triggered: a threshold already satisfied the instant the stopwatch starts is armed but
     * not fired, so `startOffset = threshold` would silently never run [action] — which is exactly
     * why that case is rejected outright.
     *
     * @throws IllegalArgumentException if [threshold] is not strictly greater than the stopwatch's
     *   start offset.
     */
    public fun actionWhen(threshold: Duration, action: suspend () -> Unit) {
        val millis = threshold.inWholeMilliseconds
        require(millis > startOffsetMillis) {
            "actionWhen threshold must be strictly greater than the start offset " +
                "($startOffsetMillis ms), was $millis"
        }
        actionEntries += ActionEntry(millis, action)
    }

    internal fun buildConfig(startFormat: String, tickPolicy: TickPolicy, onError: (Throwable) -> Unit): EngineConfig {
        val base = SemanticEntry(startOffsetMillis, PatternAnalyzer.analyze(startFormat))
        val semantics = (formatEntries + base).sortedByDescending { it.thresholdMillis }
        return EngineConfig(
            countDown = false,
            initialMillis = startOffsetMillis,
            semantics = semantics,
            actions = actionEntries.toList(),
            tickPolicy = tickPolicy,
            totalCycles = 1,
            onFinish = null,
            onCycleComplete = null,
            onError = onError,
        )
    }
}

/**
 * Builds and returns a [Stopwatch] that counts up from [startOffset].
 *
 * The receiver [CoroutineScope] does double duty: it is where the tick loop runs — pass
 * `viewModelScope` in an Android `ViewModel`, or a [kotlinx.coroutines.test.TestScope] to drive a
 * stopwatch in lockstep with virtual time in a test — and it is what [Corotimer.release] cancels a
 * child of, so releasing a stopwatch never cancels the scope it was built with.
 *
 * ```kotlin
 * val watch = viewModelScope.stopwatch(startFormat = "MM:SS") {
 *     changeFormatWhen(1.hours, "HH:MM:SS")
 * }
 * watch.start()
 * watch.lap()
 * ```
 *
 * @throws IllegalArgumentException if [startOffset] is negative or infinite, or if [startFormat]
 *   cannot be parsed.
 */
public fun CoroutineScope.stopwatch(
    startFormat: String = "MM:SS",
    startOffset: Duration = Duration.ZERO,
    clock: MonotonicClock = MonotonicClock.System,
    tickPolicy: TickPolicy = TickPolicy.Aligned,
    onError: (Throwable) -> Unit = ::defaultOnError,
    configure: StopwatchBuilder.() -> Unit = {},
): Stopwatch {
    require(startOffset.isFinite() && !startOffset.isNegative()) {
        "startOffset must be non-negative and finite, was $startOffset"
    }
    val startOffsetMillis = startOffset.inWholeMilliseconds
    val config = StopwatchBuilder(startOffsetMillis).apply(configure).buildConfig(startFormat, tickPolicy, onError)
    val semanticIndex = Reducer.selectSemanticIndex(config, startOffsetMillis)
    val initial = EngineState(
        phase = Phase.IDLE,
        configuredMillis = startOffsetMillis,
        accumulatedMillis = 0L,
        baseMillis = 0L,
        semanticIndex = semanticIndex,
        firedActions = Reducer.prearmedActions(config, startOffsetMillis),
        laps = emptyList(),
        cycle = 0,
        runId = 0L,
        rendered = Reducer.render(config, Phase.IDLE, startOffsetMillis, semanticIndex, 0),
    )
    return StopwatchImpl(Engine(config, clock, this, initial))
}

private class StopwatchImpl(private val engine: Engine) : Stopwatch {
    override val state get() = engine.state
    override val laps get() = engine.laps
    override fun lap() = engine.lap()
    override fun start() = engine.start()
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun reset() = engine.reset()
    override fun setTime(millis: Long) = engine.setTime(millis)
    override fun release() = engine.release()
}
