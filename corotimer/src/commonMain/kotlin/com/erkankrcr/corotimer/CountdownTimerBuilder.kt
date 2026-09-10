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
 * Configures a [CountdownTimer] built by [countdownTimer].
 *
 * Instances are created fresh by [countdownTimer] on every call — there is no shared, mutable
 * builder state to accidentally reuse across timers, so calling [countdownTimer] from a loop or
 * concurrently from multiple threads is always safe.
 */
public class CountdownTimerBuilder internal constructor(private val startTimeMillis: Long) {

    private val formatEntries = mutableListOf<SemanticEntry>()
    private val actionEntries = mutableListOf<ActionEntry>()
    private var onFinishCallback: (suspend () -> Unit)? = null
    private var onCycleCompleteCallback: (suspend (Int) -> Unit)? = null
    private var totalCycles: Int = 1

    /**
     * Switches the rendered format once the remaining time drops to or below [threshold].
     *
     * Level-triggered, not edge-triggered: the format in force is always whichever configured
     * threshold most tightly bounds the current remaining time, so [threshold] may equal the
     * timer's start time.
     *
     * @throws IllegalArgumentException if [threshold] is negative or greater than the timer's
     *   start time, or if [format] cannot be parsed.
     */
    public fun changeFormatWhen(threshold: Duration, format: String) {
        val millis = threshold.inWholeMilliseconds
        require(millis in 0..startTimeMillis) {
            "changeFormatWhen threshold must be between 0 and the start time ($startTimeMillis ms), was $millis"
        }
        formatEntries += SemanticEntry(millis, PatternAnalyzer.analyze(format))
    }

    /**
     * Runs [action] exactly once when the remaining time passes [threshold].
     *
     * Edge-triggered: a threshold already satisfied the instant the timer starts is armed but not
     * fired, so `startTime = threshold` would silently never run [action] — which is exactly why
     * that case is rejected outright.
     *
     * @throws IllegalArgumentException if [threshold] is negative or is not strictly less than
     *   the timer's start time.
     */
    public fun actionWhen(threshold: Duration, action: suspend () -> Unit) {
        val millis = threshold.inWholeMilliseconds
        require(millis in 0 until startTimeMillis) {
            "actionWhen threshold must be non-negative and strictly less than the start time " +
                "($startTimeMillis ms), was $millis"
        }
        actionEntries += ActionEntry(millis, action)
    }

    /** Runs [action] exactly once when the countdown finishes its last cycle. */
    public fun onFinish(action: suspend () -> Unit) {
        onFinishCallback = action
    }

    /** Runs [action] after every cycle but the last when [repeat] or [repeatForever] is configured. */
    public fun onCycleComplete(action: suspend (cycle: Int) -> Unit) {
        onCycleCompleteCallback = action
    }

    /** Restarts the countdown from its start time [times] times before finishing.
     * @throws IllegalArgumentException if [times] is less than 1. */
    public fun repeat(times: Int) {
        require(times >= 1) { "repeat count must be at least 1, was $times" }
        totalCycles = times
    }

    /** Restarts the countdown from its start time indefinitely; [onFinish] never fires. */
    public fun repeatForever() {
        totalCycles = Int.MAX_VALUE
    }

    internal fun buildConfig(startFormat: String, tickPolicy: TickPolicy, onError: (Throwable) -> Unit): EngineConfig {
        val base = SemanticEntry(startTimeMillis, PatternAnalyzer.analyze(startFormat))
        val semantics = (formatEntries + base).sortedBy { it.thresholdMillis }
        return EngineConfig(
            countDown = true,
            initialMillis = startTimeMillis,
            semantics = semantics,
            actions = actionEntries.toList(),
            tickPolicy = tickPolicy,
            totalCycles = totalCycles,
            onFinish = onFinishCallback,
            onCycleComplete = onCycleCompleteCallback,
            onError = onError,
        )
    }
}

/**
 * Builds and returns a [CountdownTimer] that counts down from [startTime] to zero.
 *
 * The receiver [CoroutineScope] does double duty: it is where the tick loop runs — pass
 * `viewModelScope` in an Android `ViewModel`, or a [kotlinx.coroutines.test.TestScope] to drive a
 * timer in lockstep with virtual time in a test — and it is what [Corotimer.release] cancels a
 * child of, so releasing a timer never cancels the scope it was built with.
 *
 * ```kotlin
 * val timer = viewModelScope.countdownTimer(startTime = 5.minutes, startFormat = "MM:SS") {
 *     changeFormatWhen(10.seconds, "SS.LL")
 *     actionWhen(3.seconds) { playTickSound() }
 *     onFinish { showTimeUpDialog() }
 * }
 * timer.start()
 * ```
 *
 * @throws IllegalArgumentException if [startTime] is not positive and finite, or if [startFormat]
 *   cannot be parsed.
 */
public fun CoroutineScope.countdownTimer(
    startTime: Duration,
    startFormat: String = "MM:SS",
    clock: MonotonicClock = MonotonicClock.System,
    tickPolicy: TickPolicy = TickPolicy.Aligned,
    onError: (Throwable) -> Unit = ::defaultOnError,
    configure: CountdownTimerBuilder.() -> Unit = {},
): CountdownTimer {
    require(startTime.isFinite() && startTime.isPositive()) {
        "startTime must be positive and finite, was $startTime"
    }
    val startTimeMillis = startTime.inWholeMilliseconds
    val config = CountdownTimerBuilder(startTimeMillis).apply(configure).buildConfig(startFormat, tickPolicy, onError)
    val semanticIndex = Reducer.selectSemanticIndex(config, startTimeMillis)
    val initial = EngineState(
        phase = Phase.IDLE,
        configuredMillis = startTimeMillis,
        accumulatedMillis = 0L,
        baseMillis = 0L,
        semanticIndex = semanticIndex,
        firedActions = Reducer.prearmedActions(config, startTimeMillis),
        laps = emptyList(),
        cycle = 0,
        runId = 0L,
        rendered = Reducer.render(config, Phase.IDLE, startTimeMillis, semanticIndex, 0),
    )
    return CountdownTimerImpl(Engine(config, clock, this, initial))
}

private class CountdownTimerImpl(private val engine: Engine) : CountdownTimer {
    override val state get() = engine.state
    override fun start() = engine.start()
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun reset() = engine.reset()
    override fun setTime(millis: Long) = engine.setTime(millis)
    override fun release() = engine.release()
}

internal fun defaultOnError(throwable: Throwable) {
    println("[corotimer] unhandled exception in a timer callback: $throwable")
}
