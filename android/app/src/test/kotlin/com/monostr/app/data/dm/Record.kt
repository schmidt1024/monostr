package com.monostr.app.data.dm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Every emission of [flow] until the test ends, collected eagerly (spec perf2 §6: "emits" and "does not emit" cases). */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.record(flow: Flow<T>): List<T> {
    val seen = ArrayList<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
    return seen
}
