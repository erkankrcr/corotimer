package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.Lap
import com.erkankrcr.corotimer.MonotonicClock
import com.erkankrcr.corotimer.TimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs one timer or stopwatch: owns the mutable snapshot, drives the tick loop, and publishes a
 * de-duplicated [TimeState] stream.
 *
 * ### Thread-safety
 *
 * All state lives in a single [MutableStateFlow] of immutable [EngineState] snapshots, mutated
 * only through [Reducer.reduce] behind a compare-and-set retry loop ([dispatch]). Public methods
 * are plain, non-suspending functions: a caller that calls [pause] and immediately reads [state]
 * sees the paused value, because the swap already happened by the time [pause] returns. No mutex,
 * no dispatcher confinement — a pure reducer plus CAS gives the same safety without making the
 * public API asynchronous.
 *
 * ### Lifecycle
 *
 * A child [Job] of [parentScope] backs every coroutine this engine launches. [release] cancels
 * only that child, never the caller's scope — starting a timer in `viewModelScope` must not risk
 * taking the whole `ViewModel` down with it.
 */
internal class Engine(
    private val config: EngineConfig,
    private val clock: MonotonicClock,
    parentScope: CoroutineScope,
    initial: EngineState,
) {
    private val childJob = Job(parentScope.coroutineContext[Job])
    private val engineScope = CoroutineScope(parentScope.coroutineContext + childJob)

    private val internalState = MutableStateFlow(initial)

    /**
     * Mirrors `internalState.value.rendered`, updated synchronously inside [dispatch] — in the
     * same call that performs the compare-and-set on [internalState], not from a coroutine
     * collecting it afterwards. That is what keeps the public API non-suspending in practice: a
     * caller that calls [pause] and immediately reads [state] sees [TimeState.Paused] without a
     * dispatcher ever having to run a collector in between. [MutableStateFlow]'s own conflation
     * (setting `.value` to an equal value is a no-op) is what de-duplicates a transition — a
     * redundant [reset], for example — that changes bookkeeping but not the rendered value.
     */
    private val publicState = MutableStateFlow(initial.rendered)
    val state: StateFlow<TimeState> = publicState.asStateFlow()

    val laps: List<Lap> get() = internalState.value.laps

    fun start() = dispatchAndLaunch(Event.Start)

    fun pause() = dispatchAndLaunch(Event.Pause)

    fun resume() = dispatchAndLaunch(Event.Resume)

    fun reset() = dispatchAndLaunch(Event.Reset)

    fun setTime(millis: Long) = dispatchAndLaunch(Event.SetTime(millis))

    fun release() {
        dispatchAndLaunch(Event.Release)
        childJob.cancel()
    }

    fun lap(): Lap? {
        val before = internalState.value.laps.size
        val transition = dispatch(Event.Lap)
        return transition.next.laps.let { if (it.size > before) it.last() else null }
    }

    /** Entry points other than [Event.Tick] only ever produce [Effect.LaunchTick]. */
    private fun dispatchAndLaunch(event: Event) {
        val transition = dispatch(event)
        for (effect in transition.effects) {
            if (effect is Effect.LaunchTick) engineScope.launch { tickLoop(effect.runId) }
        }
    }

    private fun dispatch(event: Event): Transition {
        while (true) {
            val current = internalState.value
            val now = clock.elapsedRealtimeMillis()
            val transition = Reducer.reduce(current, event, now, config)
            if (transition.next === current) return transition
            if (internalState.compareAndSet(current, transition.next)) {
                publicState.value = transition.next.rendered
                return transition
            }
        }
    }

    private suspend fun tickLoop(runId: Long) {
        while (true) {
            val current = internalState.value
            if (current.runId != runId || current.phase != Phase.RUNNING) return

            val now = clock.elapsedRealtimeMillis()
            val display = Reducer.displayOf(config, current.configuredMillis, Reducer.elapsedOf(current, now))
            delay(TickScheduler.delayMillis(config, current, display))

            val transition = dispatch(Event.Tick(runId))
            runTickEffects(transition.effects)

            if (transition.next.runId != runId || transition.next.phase != Phase.RUNNING) return
        }
    }

    /**
     * Runs the effects of a tick-driven transition inline, in order, inside this suspend
     * function. A slow or throwing callback delays the *next* tick's cadence but never the
     * displayed time, which is always recomputed from the clock rather than accumulated.
     */
    private suspend fun runTickEffects(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                is Effect.FireAction -> guarded { config.actions[effect.index].action() }
                Effect.FireOnFinish -> config.onFinish?.let { callback -> guarded { callback() } }
                is Effect.FireOnCycleComplete ->
                    config.onCycleComplete?.let { callback -> guarded { callback(effect.cycle) } }
                // A tick transition never produces this effect; kept only so `when` stays exhaustive.
                is Effect.LaunchTick -> engineScope.launch { tickLoop(effect.runId) }
            }
        }
    }

    /** Isolates a user callback: an exception reaches [EngineConfig.onError], never the tick loop. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            config.onError(throwable)
        }
    }
}
