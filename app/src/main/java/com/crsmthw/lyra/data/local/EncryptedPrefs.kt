package com.crsmthw.lyra.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS         = "lyra_prefs_key"
private const val GCM_TAG_LENGTH    = 128
private const val IV_SIZE           = 12

/**
 * Secure on-device storage backed by AES-256-GCM (Android Keystore).
 * Stores the Spotify Client ID and OAuth tokens.
 * Nothing is ever written as plain text.
 */
class EncryptedPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("lyra_secure_prefs_v2", Context.MODE_PRIVATE)

    private val secretKey: SecretKey = run {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
        }
        ks.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv         = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String = try {
        val combined   = Base64.decode(encoded, Base64.NO_WRAP)
        val iv         = combined.sliceArray(0 until IV_SIZE)
        val ciphertext = combined.sliceArray(IV_SIZE until combined.size)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    private fun getString(key: String): String {
        val encoded = prefs.getString(key, null) ?: return ""
        return decrypt(encoded)
    }

    private fun putString(key: String, value: String) {
        prefs.edit { putString(key, encrypt(value)) }
    }

    // ── Spotify Client ID (user-supplied) ───────────────────────────────────
    var clientId: String
        get()      = getString(KEY_CLIENT_ID)
        set(value) { putString(KEY_CLIENT_ID, value) }

    val hasClientId: Boolean get() = clientId.isNotBlank()

    // ── OAuth tokens ────────────────────────────────────────────────────────
    var accessToken: String
        get()      = getString(KEY_ACCESS_TOKEN)
        set(value) { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String
        get()      = getString(KEY_REFRESH_TOKEN)
        set(value) { putString(KEY_REFRESH_TOKEN, value) }

    /** Unix timestamp (ms) when the access token expires. */
    var tokenExpiry: Long
        get()      = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) { prefs.edit { putLong(KEY_TOKEN_EXPIRY, value) } }

    val isTokenValid: Boolean
        get() = accessToken.isNotBlank() &&
                System.currentTimeMillis() < (tokenExpiry - TOKEN_BUFFER_MS)

    fun saveTokens(access: String, refresh: String, expiresInSeconds: Long) {
        putString(KEY_ACCESS_TOKEN, access)
        putString(KEY_REFRESH_TOKEN, refresh)
        prefs.edit { putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000L) }
    }

    fun clearTokens() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRY)
        }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_CLIENT_ID      = "spotify_client_id"
        private const val KEY_ACCESS_TOKEN   = "access_token"
        private const val KEY_REFRESH_TOKEN  = "refresh_token"
        private const val KEY_TOKEN_EXPIRY   = "token_expiry"
        private const val TOKEN_BUFFER_MS    = 60_000L
    }
}
