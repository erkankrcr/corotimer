package com.erkankrcr.corotimer

/**
 * A [Corotimer] that counts down from a configured duration to zero.
 *
 * Build one with [countdownTimer]. [TimeState.Finished] is terminal: only [reset], [setTime] or
 * [start] leaves it, and in particular [pause] does not.
 */
public interface CountdownTimer : Corotimer
