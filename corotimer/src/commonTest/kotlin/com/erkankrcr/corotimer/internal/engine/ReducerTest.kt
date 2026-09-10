package com.erkankrcr.corotimer.internal.engine

import com.erkankrcr.corotimer.TickPolicy
import com.erkankrcr.corotimer.TimeState
import com.erkankrcr.corotimer.internal.format.PatternAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The whole state machine, tested as a pure function: no coroutines, no clock, no dispatcher.
 * [Reducer.reduce] takes an explicit `now`, so every phase transition is a plain `assertEquals`.
 */
class ReducerTest {

    private val ss = SemanticEntry(5_000L, PatternAnalyzer.analyze("SS"))

    private fun countdownConfig(
        totalCycles: Int = 1,
        actions: List<ActionEntry> = emptyList(),
        semantics: List<SemanticEntry> = listOf(ss),
        onFinish: (suspend () -> Unit)? = null,
        onCycleComplete: (suspend (Int) -> Unit)? = null,
    ) = EngineConfig(
        countDown = true,
        initialMillis = 5_000L,
        semantics = semantics,
        actions = actions,
        tickPolicy = TickPolicy.Aligned,
        totalCycles = totalCycles,
        onFinish = onFinish,
        onCycleComplete = onCycleComplete,
        onError = {},
    )

    private fun stopwatchConfig(
        actions: List<ActionEntry> = emptyList(),
        semantics: List<SemanticEntry> = listOf(SemanticEntry(0L, PatternAnalyzer.analyze("SS"))),
    ) = EngineConfig(
        countDown = false,
        initialMillis = 0L,
        semantics = semantics,
        actions = actions,
        tickPolicy = TickPolicy.Aligned,
        totalCycles = 1,
        onFinish = null,
        onCycleComplete = null,
        onError = {},
    )

    private fun idle(config: EngineConfig, now: Long = 0L): EngineState {
        val index = Reducer.selectSemanticIndex(config, config.initialMillis)
        return EngineState(
            phase = Phase.IDLE,
            configuredMillis = config.initialMillis,
            accumulatedMillis = 0L,
            baseMillis = now,
            semanticIndex = index,
            firedActions = Reducer.prearmedActions(config, config.initialMillis),
            laps = emptyList(),
            cycle = 0,
            runId = 0L,
            rendered = Reducer.render(config, Phase.IDLE, config.initialMillis, index, 0),
        )
    }

    // ---------------------------------------------------------------- Start

    @Test
    fun start_from_idle_enters_running_and_launches_tick() {
        val config = countdownConfig()
        val transition = Reducer.reduce(idle(config), Event.Start, now = 0L, config)
        assertEquals(Phase.RUNNING, transition.next.phase)
        assertEquals(1L, transition.next.runId)
        assertEquals(listOf(Effect.LaunchTick(1L)), transition.effects)
        assertIs<TimeState.Running>(transition.next.rendered)
    }

    @Test
    fun start_while_running_is_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val again = Reducer.reduce(running, Event.Start, 100L, config)
        assertSame(running, again.next)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun start_from_finished_restarts_fresh() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val finished = Reducer.reduce(running, Event.Tick(running.runId), 5_000L, config).next
        assertIs<TimeState.Finished>(finished.rendered)

        val restarted = Reducer.reduce(finished, Event.Start, 6_000L, config)
        assertEquals(Phase.RUNNING, restarted.next.phase)
        assertEquals(5_000L, restarted.next.configuredMillis)
        assertEquals(0L, restarted.next.accumulatedMillis)
    }

    @Test
    fun start_while_released_is_a_noop() {
        val config = countdownConfig()
        val released = Reducer.reduce(idle(config), Event.Release, 0L, config).next
        val again = Reducer.reduce(released, Event.Start, 100L, config)
        assertSame(released, again.next)
    }

    // ---------------------------------------------------------------- Pause / Resume

    @Test
    fun pause_while_running_freezes_elapsed_and_does_not_bump_effects() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 2_000L, config)
        assertEquals(Phase.PAUSED, paused.next.phase)
        assertEquals(2_000L, paused.next.accumulatedMillis)
        assertEquals(TimeState.Paused(3_000L, "03", 0), paused.next.rendered)
        assertTrue(paused.effects.isEmpty())
    }

    @Test
    fun pause_while_idle_is_a_noop() {
        val config = countdownConfig()
        val state = idle(config)
        val again = Reducer.reduce(state, Event.Pause, 10L, config)
        assertSame(state, again.next)
    }

    @Test
    fun pause_while_paused_is_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 1_000L, config).next
        val again = Reducer.reduce(paused, Event.Pause, 2_000L, config)
        assertSame(paused, again.next)
    }

    /** Bug 1: the reference API's `stop()` overwrote a finished countdown with `Paused(0)`. */
    @Test
    fun pause_on_finished_countdown_leaves_it_finished() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val finished = Reducer.reduce(running, Event.Tick(running.runId), 5_000L, config).next
        assertIs<TimeState.Finished>(finished.rendered)

        val stillFinished = Reducer.reduce(finished, Event.Pause, 5_500L, config)
        assertSame(finished, stillFinished.next)
        assertIs<TimeState.Finished>(stillFinished.next.rendered)
    }

    @Test
    fun resume_while_paused_re_anchors_clock_without_losing_elapsed() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 2_000L, config).next
        val resumed = Reducer.reduce(paused, Event.Resume, 9_000L, config)
        assertEquals(Phase.RUNNING, resumed.next.phase)
        assertEquals(2_000L, resumed.next.accumulatedMillis)
        assertEquals(9_000L, resumed.next.baseMillis)
        assertEquals(TimeState.Running(3_000L, "03", 0), resumed.next.rendered)
        assertEquals(listOf(Effect.LaunchTick(resumed.next.runId)), resumed.effects)
    }

    @Test
    fun resume_while_running_is_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val again = Reducer.reduce(running, Event.Resume, 100L, config)
        assertSame(running, again.next)
    }

    @Test
    fun resume_while_idle_is_a_noop() {
        val config = countdownConfig()
        val state = idle(config)
        assertSame(state, Reducer.reduce(state, Event.Resume, 10L, config).next)
    }

    // ---------------------------------------------------------------- Reset

    @Test
    fun reset_returns_to_configured_value_and_clears_laps() {
        val config = stopwatchConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val ticked = Reducer.reduce(running, Event.Tick(running.runId), 3_000L, config).next
        val lapped = Reducer.reduce(ticked, Event.Lap, 3_000L, config).next
        assertEquals(1, lapped.laps.size)

        val reset = Reducer.reduce(lapped, Event.Reset, 4_000L, config)
        assertEquals(Phase.IDLE, reset.next.phase)
        assertEquals(0L, reset.next.configuredMillis)
        assertTrue(reset.next.laps.isEmpty())
    }

    /** Bug 2: `setTime` on a paused engine used to ignore the still-elapsed accumulator. */
    @Test
    fun setTime_on_paused_countdown_resets_accumulated_elapsed() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 2_000L, config).next // 3s remaining

        val retimed = Reducer.reduce(paused, Event.SetTime(30_000L), 2_500L, config)
        assertEquals(Phase.PAUSED, retimed.next.phase)
        assertEquals(30_000L, retimed.next.configuredMillis)
        assertEquals(0L, retimed.next.accumulatedMillis)

        val resumed = Reducer.reduce(retimed.next, Event.Resume, 3_500L, config).next
        val afterOneSecond = Reducer.reduce(resumed, Event.Tick(resumed.runId), 4_500L, config).next
        assertEquals(TimeState.Running(29_000L, "29", 0), afterOneSecond.rendered)
    }

    @Test
    fun setTime_while_running_relaunches_tick_with_new_runId() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val retimed = Reducer.reduce(running, Event.SetTime(10_000L), 1_000L, config)
        assertEquals(Phase.RUNNING, retimed.next.phase)
        assertEquals(10_000L, retimed.next.configuredMillis)
        assertEquals(listOf(Effect.LaunchTick(retimed.next.runId)), retimed.effects)
        assertTrue(retimed.next.runId != running.runId)
    }

    @Test
    fun setTime_on_finished_countdown_moves_to_paused() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val finished = Reducer.reduce(running, Event.Tick(running.runId), 5_000L, config).next
        val retimed = Reducer.reduce(finished, Event.SetTime(20_000L), 5_100L, config)
        assertEquals(Phase.PAUSED, retimed.next.phase)
        assertEquals(20_000L, retimed.next.configuredMillis)
    }

    @Test
    fun setTime_while_idle_updates_displayed_value_without_starting() {
        val config = countdownConfig()
        val retimed = Reducer.reduce(idle(config), Event.SetTime(1_000L), 0L, config)
        assertEquals(Phase.IDLE, retimed.next.phase)
        assertEquals(TimeState.Idle(1_000L, "01", 0), retimed.next.rendered)
        assertTrue(retimed.effects.isEmpty())
    }

    @Test
    fun setTime_clears_fired_actions_so_they_can_refire() {
        val action = ActionEntry(3_000L) {}
        val config = countdownConfig(actions = listOf(action))
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val past3s = Reducer.reduce(running, Event.Tick(running.runId), 2_500L, config).next
        assertEquals(setOf(0), past3s.firedActions)

        val retimed = Reducer.reduce(past3s, Event.SetTime(10_000L), 2_500L, config)
        assertTrue(retimed.next.firedActions.isEmpty())
    }

    // ---------------------------------------------------------------- Release

    @Test
    fun release_marks_released_and_swallows_every_further_event() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val released = Reducer.reduce(running, Event.Release, 100L, config)
        assertEquals(Phase.RELEASED, released.next.phase)

        for (event in listOf(Event.Start, Event.Pause, Event.Resume, Event.Reset, Event.Lap, Event.Tick(released.next.runId))) {
            val again = Reducer.reduce(released.next, event, 200L, config)
            assertSame(released.next, again.next, "event $event must be a no-op once released")
        }
    }

    // ---------------------------------------------------------------- Lap

    @Test
    fun lap_on_countdown_is_always_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val again = Reducer.reduce(running, Event.Lap, 100L, config)
        assertSame(running, again.next)
    }

    @Test
    fun lap_on_idle_stopwatch_is_a_noop() {
        val config = stopwatchConfig()
        val state = idle(config)
        assertSame(state, Reducer.reduce(state, Event.Lap, 10L, config).next)
    }

    @Test
    fun lap_records_delta_from_previous_lap() {
        val config = stopwatchConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val firstLap = Reducer.reduce(running, Event.Lap, 1_000L, config).next
        assertEquals(1, firstLap.laps.size)
        assertEquals(1_000L, firstLap.laps[0].lapMillis)
        assertEquals(1_000L, firstLap.laps[0].splitMillis)

        val secondLap = Reducer.reduce(firstLap, Event.Lap, 2_500L, config).next
        assertEquals(2, secondLap.laps.size)
        assertEquals(1_500L, secondLap.laps[1].lapMillis)
        assertEquals(2_500L, secondLap.laps[1].splitMillis)
    }

    @Test
    fun lap_while_paused_is_allowed_and_uses_frozen_elapsed() {
        val config = stopwatchConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 1_500L, config).next
        val lapped = Reducer.reduce(paused, Event.Lap, 9_999L, config)
        assertEquals(1, lapped.next.laps.size)
        assertEquals(1_500L, lapped.next.laps[0].splitMillis)
    }

    // ---------------------------------------------------------------- Tick

    @Test
    fun tick_with_stale_runId_is_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val again = Reducer.reduce(running, Event.Tick(running.runId - 1), 1_000L, config)
        assertSame(running, again.next)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun tick_while_not_running_is_a_noop() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val paused = Reducer.reduce(running, Event.Pause, 1_000L, config).next
        val again = Reducer.reduce(paused, Event.Tick(paused.runId), 2_000L, config)
        assertSame(paused, again.next)
    }

    /** Bug 3: a tick already in flight when `reset()` fires must never resurrect `Running`. */
    @Test
    fun tick_after_reset_cannot_publish_a_ghost_running_state() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val staleRunId = running.runId
        val reset = Reducer.reduce(running, Event.Reset, 500L, config).next

        val ghostTick = Reducer.reduce(reset, Event.Tick(staleRunId), 600L, config)
        assertSame(reset, ghostTick.next)
        assertIs<TimeState.Idle>(ghostTick.next.rendered)
    }

    @Test
    fun tick_reaching_zero_finishes_and_fires_onFinish() {
        var fired = false
        val config = countdownConfig(onFinish = { fired = true })
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val transition = Reducer.reduce(running, Event.Tick(running.runId), 5_000L, config)
        assertEquals(Phase.FINISHED, transition.next.phase)
        assertEquals(TimeState.Finished("00", 0), transition.next.rendered)
        assertEquals(listOf(Effect.FireOnFinish), transition.effects)
        assertTrue(!fired) // the reducer only *returns* the effect; running it is the caller's job.
    }

    /** Bug 7: an action whose threshold equals the start value must not fire at t=0. */
    @Test
    fun action_already_satisfied_at_start_is_armed_but_not_fired() {
        val action = ActionEntry(5_000L) {}
        val config = countdownConfig(actions = listOf(action))
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        assertEquals(setOf(0), running.firedActions, "threshold already satisfied at start must be pre-armed")

        val transition = Reducer.reduce(running, Event.Tick(running.runId), 100L, config)
        assertTrue(
            transition.effects.none { it is Effect.FireAction },
            "a pre-armed action must not fire just because a tick happened",
        )
    }

    @Test
    fun action_fires_exactly_once_when_threshold_is_crossed() {
        val action = ActionEntry(3_000L) {}
        val config = countdownConfig(actions = listOf(action))
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val crossed = Reducer.reduce(running, Event.Tick(running.runId), 2_100L, config)
        assertEquals(listOf(Effect.FireAction(0)), crossed.effects)

        val again = Reducer.reduce(crossed.next, Event.Tick(running.runId), 2_500L, config)
        assertTrue(again.effects.none { it is Effect.FireAction })
    }

    @Test
    fun action_between_two_ticks_still_fires_on_the_next_tick() {
        val action = ActionEntry(3_000L) {}
        val config = countdownConfig(actions = listOf(action))
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        // Jump straight from 5000 remaining to 1000 remaining: the 3000ms threshold was crossed
        // in between, and must still be reported on this single tick.
        val transition = Reducer.reduce(running, Event.Tick(running.runId), 4_000L, config)
        assertEquals(listOf(Effect.FireAction(0)), transition.effects)
    }

    @Test
    fun repeat_rolls_into_next_cycle_without_a_new_tick_job() {
        val cycles = mutableListOf<Int>()
        val config = countdownConfig(totalCycles = 2, onCycleComplete = { cycles.add(it) })
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val transition = Reducer.reduce(running, Event.Tick(running.runId), 5_000L, config)
        assertEquals(Phase.RUNNING, transition.next.phase)
        assertEquals(1, transition.next.cycle)
        assertEquals(5_000L, transition.next.configuredMillis)
        assertEquals(running.runId, transition.next.runId, "same tick job must keep ticking across a cycle boundary")
        assertTrue(transition.effects.contains(Effect.FireOnCycleComplete(0)))
        assertTrue(transition.effects.none { it is Effect.LaunchTick })
    }

    @Test
    fun repeat_fires_onFinish_only_after_the_last_cycle() {
        val config = countdownConfig(totalCycles = 2, onFinish = {})
        var state = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val afterCycle0 = Reducer.reduce(state, Event.Tick(state.runId), 5_000L, config)
        assertTrue(afterCycle0.effects.none { it is Effect.FireOnFinish })

        state = afterCycle0.next
        val afterCycle1 = Reducer.reduce(state, Event.Tick(state.runId), 10_000L, config)
        assertEquals(Phase.FINISHED, afterCycle1.next.phase)
        assertTrue(afterCycle1.effects.contains(Effect.FireOnFinish))
    }

    // ---------------------------------------------------------------- Stopwatch direction

    @Test
    fun stopwatch_display_counts_up_and_never_finishes() {
        val config = stopwatchConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 0L, config).next
        val ticked = Reducer.reduce(running, Event.Tick(running.runId), 90_000L, config).next
        assertIs<TimeState.Running>(ticked.rendered)
        assertEquals(90_000L, ticked.rendered.currentMillis)
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun saturatingAdd_clamps_instead_of_wrapping() {
        assertEquals(Long.MAX_VALUE, Reducer.saturatingAdd(Long.MAX_VALUE - 10, 100))
        assertEquals(Long.MIN_VALUE, Reducer.saturatingAdd(Long.MIN_VALUE + 10, -100))
        assertEquals(30L, Reducer.saturatingAdd(10, 20))
    }

    @Test
    fun elapsedOf_defends_against_a_clock_that_moves_backwards() {
        val config = countdownConfig()
        val running = Reducer.reduce(idle(config), Event.Start, 100L, config).next
        assertEquals(0L, Reducer.elapsedOf(running, now = 50L))
    }
}
