package org.ferabrasil.quests.platform

/**
 * Small-secret storage backed by the platform keystore (app.md §4).
 *
 * Implemented by each app in its own stack: Android Keystore (Kotlin, `:androidApp`)
 * and Keychain (Swift, `iosApp/`). Methods are synchronous so that Swift can implement
 * the protocol directly (`throws` in Swift); callers run them off the main thread.
 *
 * Contract: [get] returns null **only** when the secret does not exist. Any other failure
 * (locked keystore, corrupt blob, …) must throw — otherwise callers would treat an
 * unreadable identity as absent and overwrite it.
 */
interface SecretStore {
    @Throws(SecretStoreException::class)
    fun get(name: String): ByteArray?

    @Throws(SecretStoreException::class)
    fun put(name: String, value: ByteArray)

    /** Deleting a missing secret succeeds. */
    @Throws(SecretStoreException::class)
    fun delete(name: String)
}

class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
