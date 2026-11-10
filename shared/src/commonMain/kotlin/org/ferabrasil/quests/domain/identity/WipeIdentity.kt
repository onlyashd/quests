package org.ferabrasil.quests.domain.identity

import dev.zacsweers.metro.Inject
import org.ferabrasil.quests.domain.preferences.PreferencesRepository

/**
 * Settings → Wipe identity (app.md §5.4): deletes the SecretStore entry and local preferences.
 * The vault and caches join this in A1.
 */
@Inject
class WipeIdentity(
    private val identity: IdentityRepository,
    private val preferences: PreferencesRepository,
) {
    /** The user must type this word (localized display, canonical value compared case-insensitively). */
    fun isConfirmed(typed: String, expected: String): Boolean =
        typed.trim().equals(expected.trim(), ignoreCase = true) && expected.isNotBlank()

    @Throws(Exception::class)
    suspend operator fun invoke() {
        identity.wipe()
        preferences.clear()
    }
}
