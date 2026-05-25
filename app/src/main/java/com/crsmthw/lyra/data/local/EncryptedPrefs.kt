package com.crsmthw.lyra.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure on-device storage backed by AES-256-GCM (Android Keystore).
 * Stores the Spotify Client ID and OAuth tokens.
 * Nothing is ever written as plain text.
 */
class EncryptedPrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "lyra_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ── Spotify Client ID (user-supplied) ───────────────────────────────────
    var clientId: String
        get()      = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) { prefs.edit { putString(KEY_CLIENT_ID, value) } }

    val hasClientId: Boolean get() = clientId.isNotBlank()

    // ── OAuth tokens ────────────────────────────────────────────────────────
    var accessToken: String
        get()      = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""
        set(value) { prefs.edit { putString(KEY_ACCESS_TOKEN, value) } }

    var refreshToken: String
        get()      = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""
        set(value) { prefs.edit { putString(KEY_REFRESH_TOKEN, value) } }

    /** Unix timestamp (ms) when the access token expires. */
    var tokenExpiry: Long
        get()      = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) { prefs.edit { putLong(KEY_TOKEN_EXPIRY, value) } }

    val isTokenValid: Boolean
        get() = accessToken.isNotBlank() &&
                System.currentTimeMillis() < (tokenExpiry - TOKEN_BUFFER_MS)

    fun saveTokens(access: String, refresh: String, expiresInSeconds: Long) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
            putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000L)
        }
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
        private const val TOKEN_BUFFER_MS    = 60_000L   // refresh 60 s early
    }
}
