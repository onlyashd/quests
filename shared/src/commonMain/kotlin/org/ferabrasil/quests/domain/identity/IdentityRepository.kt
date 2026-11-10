package org.ferabrasil.quests.domain.identity

import dev.whyoleg.cryptography.random.CryptographyRandom
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.ferabrasil.quests.di.IoDispatcher
import org.ferabrasil.quests.platform.SecretStore

enum class IdentityState { LOADING, ABSENT, PRESENT }

/**
 * Owns the root entropy (the 24-word mnemonic's 256 bits, app.md §5.1, protocol §3.1).
 * The entropy exists only in [SecretStore] and in transient memory (C1).
 *
 * Suspend functions declare `@Throws` so Swift sees them as `async throws` instead of crashing.
 *
 * Mnemonic display, backup verification and restore arrive with BIP-39 in milestone A1.
 */
@SingleIn(AppScope::class)
@Inject
class IdentityRepository(
    private val secrets: SecretStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(IdentityState.LOADING)
    val state: StateFlow<IdentityState> = _state.asStateFlow()

    @Throws(Exception::class)
    suspend fun load() {
        val present = withContext(io) { secrets.get(ENTROPY_KEY)?.also { it.fill(0) } != null }
        _state.value = if (present) IdentityState.PRESENT else IdentityState.ABSENT
    }

    /** Generates fresh 256-bit entropy and stores it. Fails if an identity already exists. */
    @Throws(Exception::class)
    suspend fun create() {
        withContext(io) {
            check(secrets.get(ENTROPY_KEY)?.also { it.fill(0) } == null) { "identity already exists" }
            val entropy = CryptographyRandom.nextBytes(ENTROPY_BYTES)
            try {
                secrets.put(ENTROPY_KEY, entropy)
            } finally {
                entropy.fill(0)
            }
        }
        _state.value = IdentityState.PRESENT
    }

    /** Settings → Wipe identity (app.md §5.4). The vault and caches are wiped alongside it (A1). */
    @Throws(Exception::class)
    suspend fun wipe() {
        withContext(io) {
            secrets.delete(ENTROPY_KEY)
        }
        _state.value = IdentityState.ABSENT
    }

    companion object {
        const val ENTROPY_KEY = "root_entropy"
        const val ENTROPY_BYTES = 32
    }
}
