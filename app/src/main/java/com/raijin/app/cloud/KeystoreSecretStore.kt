package com.raijin.app.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed secret storage (PRD section 6, BR-2 prerequisite).
 *
 * AES-256-GCM with a key generated inside the Android Keystore: the key never
 * leaves secure hardware and is never exported. Secrets are never logged and
 * never backed up (the app already sets allowBackup=false). If the key is
 * invalidated (e.g. lock-screen change), decryption fails closed and the
 * secret is treated as absent rather than falling back to plaintext.
 */
class KeystoreSecretStore(
    private val keyAlias: String = "raijin_provider_keys",
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns Base64(iv || ciphertext||tag), or null when [plaintext] is blank. */
    fun encrypt(plaintext: String): String? {
        if (plaintext.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "Keystore encrypt failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Decrypts a value produced by [encrypt]; null on blank input or any failure. */
    fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > GCM_IV_LENGTH) { "truncated secret" }
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Keystore decrypt failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "KeystoreSecretStore"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
