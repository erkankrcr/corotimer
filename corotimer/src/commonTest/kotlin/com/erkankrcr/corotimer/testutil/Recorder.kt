package com.erkankrcr.corotimer.testutil

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Collects every value [flow] emits into [values], for deterministic ordering assertions in
 * tests. Launched on [scope] — pass `backgroundScope` from `runTest` so the collector is
 * cancelled automatically at the end of the test, even for a stopwatch whose tick loop never
 * completes on its own.
 */
internal class Recorder<T>(scope: CoroutineScope, flow: Flow<T>) {
    val values = mutableListOf<T>()

    init {
        scope.launch { flow.collect { values.add(it) } }
    }
}
