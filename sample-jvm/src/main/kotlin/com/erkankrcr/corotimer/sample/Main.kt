package com.erkankrcr.corotimer.sample

import com.erkankrcr.corotimer.TimeState
import com.erkankrcr.corotimer.countdownTimer
import com.erkankrcr.corotimer.stopwatch
import com.erkankrcr.corotimer.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A plain JVM module that depends on `:corotimer` the way any real consumer would: only public
 * imports, no internal access. Compiling this module is itself a check that the library's public
 * surface is self-contained and that `explicitApi()` never let an internal type leak into it.
 *
 * A block-bodied `fun main()` here is deliberate, not stylistic: `fun main() = runBlocking { }`
 * infers a `kotlin.Unit`-returning `main()` in the compiled class, which the JVM launcher does not
 * recognize as an entry point — only a block body compiles down to the `void main(String[])` it
 * requires.
 */
fun main() {
    runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())

        println("--- countdown ---")
        scope.countdownTimer(startTime = 3.seconds, startFormat = "SS") {
            actionWhen(1.seconds) { println("1 second to go!") }
            onFinish { println("Countdown finished.") }
        }.use { timer ->
            scope.launch {
                timer.state.collect { state: TimeState ->
                    println("countdown: ${state.formattedTime} (${state::class.simpleName})")
                }
            }
            timer.start()
            while (timer.state.value !is TimeState.Finished) delay(50.milliseconds)
        }

        println("--- stopwatch ---")
        scope.stopwatch(startFormat = "SS.LL") {
            changeFormatWhen(1.seconds, "MM:SS.LL")
        }.use { watch ->
            watch.start()
            delay(250.milliseconds)
            val lap = watch.lap()
            println("lap: ${lap?.formattedSplit}")
            watch.pause()
            println("final: ${watch.state.value.formattedTime}")
        }

        scope.coroutineContext[Job]?.cancelAndJoin()
    }
}
