package org.ferabrasil.quests.domain.preferences

import com.russhwolf.settings.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val leaderMode: Boolean = false,
)

/**
 * Non-secret UI preferences (app.md §3: multiplatform-settings for these only).
 * Nothing identity-bearing may be stored here.
 */
@SingleIn(AppScope::class)
@Inject
class PreferencesRepository(private val settings: Settings) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferences> = _state.asStateFlow()

    fun setTheme(theme: ThemeMode) {
        settings.putString(KEY_THEME, theme.name)
        _state.value = read()
    }

    fun setLeaderMode(enabled: Boolean) {
        settings.putBoolean(KEY_LEADER_MODE, enabled)
        _state.value = read()
    }

    /** Part of Wipe identity: preferences go too. */
    fun clear() {
        settings.remove(KEY_THEME)
        settings.remove(KEY_LEADER_MODE)
        _state.value = read()
    }

    private fun read() = AppPreferences(
        theme = settings.getStringOrNull(KEY_THEME)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM,
        leaderMode = settings.getBoolean(KEY_LEADER_MODE, false),
    )

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_LEADER_MODE = "leader_mode"
    }
}
