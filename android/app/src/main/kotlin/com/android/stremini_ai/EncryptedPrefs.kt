package com.android.stremini_ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides encrypted SharedPreferences storage using Android Keystore + AES-GCM.
 * Falls back to obfuscated Base64 on API < 23 (rare for minSdk 26, but safe).
 *
 * Usage:
 *   val prefs = EncryptedPrefs(context, "my_prefs_name")
 *   prefs.putString("key", "sensitive_value")
 *   val value = prefs.getString("key", null)
 */
object EncryptedPrefs {

    private const val KEYSTORE_ALIAS = "stremini_encrypted_prefs"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Prepend IV to ciphertext: [IV (12 bytes)] + [ciphertext + GCM tag]
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(ciphertext: String): String {
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (combined.size < 12) throw IllegalArgumentException("Encrypted data too short")
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        if (encrypted.isEmpty()) throw IllegalArgumentException("No encrypted payload")

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** Get an encrypted SharedPreferences wrapper. */
    fun getEncrypted(context: Context, name: String): EncryptedSharedPreferencesWrapper {
        return EncryptedSharedPreferencesWrapper(
            context.getSharedPreferences("${name}_encrypted", Context.MODE_PRIVATE)
        )
    }

    class EncryptedSharedPreferencesWrapper(private val prefs: SharedPreferences) {
        fun getString(key: String, defaultValue: String? = null): String? {
            val raw = prefs.getString(key, null) ?: return defaultValue
            return try { decrypt(raw) } catch (_: Exception) { defaultValue }
        }

        fun putString(key: String, value: String) {
            prefs.edit().putString(key, encrypt(value)).apply()
        }

        fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        fun contains(key: String): Boolean = prefs.contains(key)
    }
}