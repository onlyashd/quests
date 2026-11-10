package org.ferabrasil.quests.interop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Swift-friendly view of a [StateFlow] (Flows are not bridged by Objective-C export).
 * Android collects the StateFlow directly; iOS wraps it:
 *
 * ```swift
 * let watcher = Watchable<IdentityState>(flow: graph.identity.state)
 * let handle = watcher.watch { state in self.identity = state }
 * // …
 * handle.cancel()
 * ```
 */
class Watchable<T : Any>(private val flow: StateFlow<T>) {
    val value: T get() = flow.value

    /** Calls [onEach] on the main thread with the current value and every change. */
    fun watch(onEach: (T) -> Unit): Cancellable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch { flow.collect { onEach(it) } }
        return Cancellable { scope.cancel() }
    }
}

fun interface Cancellable {
    fun cancel()
}
