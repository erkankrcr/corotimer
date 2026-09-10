package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.Lap
import com.erkankrcr.corotimer.TimeState
import com.erkankrcr.corotimer.internal.format.SemanticFormatter

/**
 * The whole state machine as one pure function.
 *
 * Nothing here reads a clock, touches a coroutine or runs a callback: [now] is passed in and side
 * effects are returned. That makes every transition — including the ones that used to be racy —
 * testable with plain assertions and no test dispatcher.
 *
 * A transition that changes nothing returns the *same instance* it was given. Callers use
 * referential identity to detect a no-op, so this must not be relaxed to an equal-but-new copy.
 */
internal object Reducer {

    fun reduce(current: EngineState, event: Event, now: Long, config: EngineConfig): Transition {
        if (current.phase == Phase.RELEASED) return Transition(current, emptyList())

        return when (event) {
            Event.Start -> start(current, now, config)
            Event.Resume -> if (current.phase == Phase.PAUSED) start(current, now, config) else noop(current)
            Event.Pause -> pause(current, now, config)
            Event.Reset -> reset(current, now, config)
            Event.Release -> Transition(
                current.copy(phase = Phase.RELEASED, runId = current.runId + 1),
                emptyList(),
            )
            Event.Lap -> lap(current, now, config)
            is Event.SetTime -> setTime(current, event.millis, now, config)
            is Event.Tick -> tick(current, event.runId, now, config)
        }
    }

    private fun noop(current: EngineState) = Transition(current, emptyList())

    // ---------------------------------------------------------------- transitions

    private fun start(current: EngineState, now: Long, config: EngineConfig): Transition = when (current.phase) {
        Phase.RUNNING, Phase.RELEASED -> noop(current)

        // Resuming keeps the elapsed time frozen at the pause and simply re-anchors the clock.
        Phase.PAUSED -> {
            val runId = current.runId + 1
            val display = displayOf(config, current.configuredMillis, current.accumulatedMillis)
            Transition(
                current.copy(
                    phase = Phase.RUNNING,
                    baseMillis = now,
                    runId = runId,
                    rendered = render(config, Phase.RUNNING, display, current.semanticIndex, current.cycle),
                ),
                listOf(Effect.LaunchTick(runId)),
            )
        }

        Phase.IDLE, Phase.FINISHED -> {
            val runId = current.runId + 1
            Transition(
                freshRun(current, config, now, current.configuredMillis, Phase.RUNNING, cycle = 0, runId = runId),
                listOf(Effect.LaunchTick(runId)),
            )
        }
    }

    /**
     * Only a running timer can be paused. A finished countdown stays finished — that guard is the
     * whole fix for the reference bug where "stop" overwrote `Finished` with `Paused(0)`.
     */
    private fun pause(current: EngineState, now: Long, config: EngineConfig): Transition {
        if (current.phase != Phase.RUNNING) return noop(current)
        val elapsed = elapsedOf(current, now)
        val display = displayOf(config, current.configuredMillis, elapsed)
        return Transition(
            current.copy(
                phase = Phase.PAUSED,
                accumulatedMillis = elapsed,
                runId = current.runId + 1,
                rendered = render(config, Phase.PAUSED, display, current.semanticIndex, current.cycle),
            ),
            emptyList(),
        )
    }

    /** Returns to the value the builder was configured with, discarding any `setTime`. */
    private fun reset(current: EngineState, now: Long, config: EngineConfig): Transition =
        Transition(
            freshRun(
                current = current,
                config = config,
                now = now,
                configuredMillis = config.initialMillis,
                phase = Phase.IDLE,
                cycle = 0,
                runId = current.runId + 1,
            ).copy(laps = emptyList()),
            emptyList(),
        )

    /**
     * Legal in every phase, including while running, because the clock is re-anchored in the same
     * atomic swap that changes the value.
     *
     * Clearing [EngineState.accumulatedMillis] is what the reference forgot: it left the old
     * elapsed time in place, so resuming a re-timed countdown immediately jumped forward by
     * however long the previous run had been going.
     */
    private fun setTime(current: EngineState, millis: Long, now: Long, config: EngineConfig): Transition {
        val runId = current.runId + 1
        val phase = if (current.phase == Phase.FINISHED) Phase.PAUSED else current.phase
        val next = freshRun(current, config, now, millis, phase, current.cycle, runId)
        val effects = if (phase == Phase.RUNNING) listOf(Effect.LaunchTick(runId)) else emptyList()
        return Transition(next, effects)
    }

    /** Laps are a stopwatch concept; a countdown returns the snapshot untouched. */
    private fun lap(current: EngineState, now: Long, config: EngineConfig): Transition {
        if (config.countDown) return noop(current)
        if (current.phase != Phase.RUNNING && current.phase != Phase.PAUSED) return noop(current)

        val elapsed = elapsedOf(current, now)
        val split = displayOf(config, current.configuredMillis, elapsed)
        val previousSplit = current.laps.lastOrNull()?.splitMillis ?: current.configuredMillis
        val lapMillis = (split - previousSplit).coerceAtLeast(0L)
        val semantic = config.semantics[current.semanticIndex].semantic

        val recorded = Lap(
            number = current.laps.size + 1,
            lapMillis = lapMillis,
            splitMillis = split,
            formattedLap = SemanticFormatter.format(semantic, lapMillis),
            formattedSplit = SemanticFormatter.format(semantic, split),
        )
        return Transition(current.copy(laps = current.laps + recorded), emptyList())
    }

    private fun tick(current: EngineState, runId: Long, now: Long, config: EngineConfig): Transition {
        // The stale-tick fence: a tick from a superseded run must never publish.
        if (runId != current.runId || current.phase != Phase.RUNNING) return noop(current)

        val elapsed = elapsedOf(current, now)
        val display = displayOf(config, current.configuredMillis, elapsed)
        val semanticIndex = selectSemanticIndex(config, display)
        val newlyFired = pendingActions(config, display, current.firedActions)
        val fired = if (newlyFired.isEmpty()) current.firedActions else current.firedActions + newlyFired
        val actionEffects = newlyFired.map { Effect.FireAction(it) }

        if (!config.countDown || display > 0L) {
            return Transition(
                current.copy(
                    semanticIndex = semanticIndex,
                    firedActions = fired,
                    rendered = render(config, Phase.RUNNING, display, semanticIndex, current.cycle),
                ),
                actionEffects,
            )
        }

        val hasAnotherCycle = current.cycle + 1 < config.totalCycles
        return if (hasAnotherCycle) {
            // Roll straight into the next cycle without stopping: the tick loop keeps its runId.
            Transition(
                freshRun(current, config, now, current.configuredMillis, Phase.RUNNING, current.cycle + 1, current.runId)
                    .copy(firedActions = prearmedActions(config, current.configuredMillis)),
                actionEffects + Effect.FireOnCycleComplete(current.cycle),
            )
        } else {
            val finishIndex = selectSemanticIndex(config, 0L)
            Transition(
                current.copy(
                    phase = Phase.FINISHED,
                    accumulatedMillis = elapsed,
                    semanticIndex = finishIndex,
                    firedActions = fired,
                    rendered = render(config, Phase.FINISHED, 0L, finishIndex, current.cycle),
                ),
                actionEffects + Effect.FireOnFinish,
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Rebuilds a snapshot as if the timer had just been configured with [configuredMillis]:
     * elapsed time cleared, clock re-anchored, thresholds re-armed and the format re-selected.
     */
    private fun freshRun(
        current: EngineState,
        config: EngineConfig,
        now: Long,
        configuredMillis: Long,
        phase: Phase,
        cycle: Int,
        runId: Long,
    ): EngineState {
        val semanticIndex = selectSemanticIndex(config, configuredMillis)
        return current.copy(
            phase = phase,
            configuredMillis = configuredMillis,
            accumulatedMillis = 0L,
            baseMillis = now,
            semanticIndex = semanticIndex,
            firedActions = prearmedActions(config, configuredMillis),
            cycle = cycle,
            runId = runId,
            rendered = render(config, phase, configuredMillis, semanticIndex, cycle),
        )
    }

    /**
     * A countdown renders with the same "any time remaining in this unit still counts" ceiling a
     * kitchen timer uses: 4001..5000ms remaining all read "05" with an `SS` format, only becoming
     * "04" once a full second has actually elapsed. Feeding the formatter's floor-division
     * `display + (granularity - 1)` produces that ceiling without teaching the (direction-agnostic,
     * publicly reusable) [SemanticFormatter] anything about counting down. A stopwatch has no such
     * adjustment: counting up, floor is already the expected "00" for the whole first second.
     */
    fun render(config: EngineConfig, phase: Phase, display: Long, semanticIndex: Int, cycle: Int): TimeState {
        val semantic = config.semantics[semanticIndex].semantic
        val displayForFormatting = if (config.countDown && display > 0L) {
            saturatingAdd(display, semantic.granularityMillis - 1)
        } else {
            display
        }
        val formatted = SemanticFormatter.format(semantic, displayForFormatting)
        return when (phase) {
            Phase.IDLE -> TimeState.Idle(display, formatted, cycle)
            Phase.RUNNING -> TimeState.Running(display, formatted, cycle)
            Phase.PAUSED, Phase.RELEASED -> TimeState.Paused(display, formatted, cycle)
            Phase.FINISHED -> TimeState.Finished(formatted, cycle)
        }
    }

    /**
     * Elapsed time on the current run.
     *
     * The `coerceAtLeast(0)` defends against a clock that goes backwards. That should be
     * impossible for a monotonic source, but a caller-supplied [com.erkankrcr.corotimer.MonotonicClock]
     * can be anything, and the alternative is a negative displayed time.
     */
    fun elapsedOf(state: EngineState, now: Long): Long =
        if (state.phase == Phase.RUNNING) {
            saturatingAdd(state.accumulatedMillis, (now - state.baseMillis).coerceAtLeast(0L))
        } else {
            state.accumulatedMillis
        }

    fun displayOf(config: EngineConfig, configuredMillis: Long, elapsed: Long): Long =
        if (config.countDown) {
            (configuredMillis - elapsed).coerceAtLeast(0L)
        } else {
            saturatingAdd(configuredMillis, elapsed)
        }

    /**
     * Index of the format that applies at [display]. Entries are pre-sorted so the first match
     * wins; if nothing matches (only reachable above the coarsest countdown threshold) the last
     * entry — which is always the start format — applies.
     */
    fun selectSemanticIndex(config: EngineConfig, display: Long): Int {
        val index = config.semantics.indexOfFirst { crossed(config.countDown, display, it.thresholdMillis) }
        return if (index >= 0) index else config.semantics.lastIndex
    }

    /**
     * Thresholds already satisfied at the moment a run begins are recorded as fired *without*
     * firing. Actions are edge-triggered: `actionWhen` means "when the time passes this", not
     * "whenever the time is past this", so starting a five-second countdown must not immediately
     * fire an action registered at five seconds.
     */
    fun prearmedActions(config: EngineConfig, display: Long): Set<Int> {
        val armed = mutableSetOf<Int>()
        config.actions.forEachIndexed { index, entry ->
            if (crossed(config.countDown, display, entry.thresholdMillis)) armed += index
        }
        return armed
    }

    private fun pendingActions(config: EngineConfig, display: Long, fired: Set<Int>): List<Int> {
        var pending: MutableList<Int>? = null
        config.actions.forEachIndexed { index, entry ->
            if (index !in fired && crossed(config.countDown, display, entry.thresholdMillis)) {
                (pending ?: mutableListOf<Int>().also { pending = it }) += index
            }
        }
        return pending ?: emptyList()
    }

    private fun crossed(countDown: Boolean, display: Long, threshold: Long): Boolean =
        if (countDown) display <= threshold else display >= threshold

    /** Addition that saturates instead of wrapping, so a stopwatch cannot show a negative time. */
    fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        // Overflow happened iff the operands share a sign that the result does not.
        return if ((a xor sum) and (b xor sum) < 0L) {
            if (a > 0L) Long.MAX_VALUE else Long.MIN_VALUE
        } else {
            sum
        }
    }
}
