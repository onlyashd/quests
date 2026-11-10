package org.ferabrasil.quests.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ferabrasil.quests.di.AppGraph
import org.ferabrasil.quests.domain.identity.IdentityState
import org.ferabrasil.quests.domain.preferences.AppPreferences
import org.ferabrasil.quests.domain.preferences.ThemeMode

data class RootUiState(
    val identity: IdentityState = IdentityState.LOADING,
    val preferences: AppPreferences = AppPreferences(),
    val busy: Boolean = false,
    val error: Boolean = false,
)

/** Android presentation state for the app shell, built on the shared repositories. */
class RootViewModel(private val graph: AppGraph) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow(false)

    val state: StateFlow<RootUiState> = combine(
        graph.identity.state, graph.preferences.state, busy, error,
    ) { identity, prefs, b, e -> RootUiState(identity, prefs, b, e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    fun wipeWordMatches(typed: String, expected: String): Boolean = graph.wipeIdentity.isConfirmed(typed, expected)

    init {
        run { graph.identity.load() }
    }

    fun createIdentity() = run { graph.identity.create() }

    fun wipeIdentity() = run { graph.wipeIdentity() }

    fun setTheme(theme: ThemeMode) = graph.preferences.setTheme(theme)

    fun setLeaderMode(enabled: Boolean) = graph.preferences.setLeaderMode(enabled)

    fun dismissError() {
        error.value = false
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: Exception) {
                error.value = true
            } finally {
                busy.value = false
            }
        }
    }
}
