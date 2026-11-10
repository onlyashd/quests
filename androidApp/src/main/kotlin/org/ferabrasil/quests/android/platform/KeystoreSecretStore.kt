package org.ferabrasil.quests.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.ferabrasil.quests.platform.SecretStore
import org.ferabrasil.quests.platform.SecretStoreException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * app.md §4 `SecretStore` on Android: a random AES-256-GCM key in Android Keystore
 * (StrongBox when available, unlocked-device required, no user auth) wraps each secret;
 * the wrapped blob lives in `noBackupFilesDir`. Blob layout: `iv(12) ‖ ciphertext+tag`,
 * with the secret's name as AAD so blobs can't be swapped between names.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val appContext = context.applicationContext
    private val dir = File(appContext.noBackupFilesDir, "secrets")

    @Synchronized
    override fun get(name: String): ByteArray? {
        val file = fileFor(name)
        if (!file.exists()) return null
        return wrap("read") {
            val blob = file.readBytes()
            if (blob.size <= IV_BYTES) throw SecretStoreException("corrupt secret blob")
            val key = existingKey() ?: throw SecretStoreException("keystore key missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.updateAAD(name.encodeToByteArray())
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }
    }

    @Synchronized
    override fun put(name: String, value: ByteArray) {
        val file = fileFor(name)
        wrap("write") {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey()) // Keystore picks the IV
            cipher.updateAAD(name.encodeToByteArray())
            val ct = cipher.doFinal(value)
            val iv = cipher.iv
            check(iv.size == IV_BYTES)
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(iv + ct)
            if (!tmp.renameTo(file)) throw SecretStoreException("could not persist secret")
        }
    }

    @Synchronized
    override fun delete(name: String) {
        val file = fileFor(name)
        if (file.exists() && !file.delete()) throw SecretStoreException("could not delete secret")
    }

    private fun fileFor(name: String): File {
        require(NAME.matches(name)) { "invalid secret name" }
        return File(dir, "$name.bin")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as SecretKey?

    private fun generateKey(): SecretKey {
        val strongBox = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generateKey(strongBox)
        } catch (e: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private inline fun <T> wrap(op: String, block: () -> T): T = try {
        block()
    } catch (e: SecretStoreException) {
        throw e
    } catch (e: Exception) {
        throw SecretStoreException("secret $op failed", e)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aqe.secret-store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val NAME = Regex("[a-z0-9_]{1,64}")
    }
}
