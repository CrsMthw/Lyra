package com.crsmthw.lyra.data.auth

import com.crsmthw.lyra.data.local.EncryptedPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that attaches the Bearer token to every API request
 * and transparently refreshes it when expired.
 */
class TokenManager(
    private val encryptedPrefs: EncryptedPrefs,
    private val authManager   : SpotifyAuthManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Refresh token proactively if close to expiry. Double-checked lock prevents multiple
        // concurrent OkHttp threads from each firing a refresh with the same refresh token.
        if (!encryptedPrefs.isTokenValid && encryptedPrefs.refreshToken.isNotBlank()) {
            synchronized(this) {
                if (!encryptedPrefs.isTokenValid && encryptedPrefs.refreshToken.isNotBlank()) {
                    runBlocking { authManager.refreshAccessToken() }
                }
            }
        }

        val usedToken = encryptedPrefs.accessToken
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $usedToken")
            .build()

        val response = chain.proceed(request)

        // 401 = the token we sent was rejected (expired locally, or revoked server-side while still
        // within our local expiry window). Refresh and retry ONCE — but only if a refresh token
        // still exists and the refresh actually succeeds. If the refresh fails with invalid_grant,
        // refreshAccessToken() has already discarded the tokens and signalled the UI; we must NOT
        // retry (Spotify's requirement) and must return the original 401 to the caller. Close the
        // body only when we commit to a retry — an OkHttp response body can be read once.
        if (response.code == 401 && encryptedPrefs.refreshToken.isNotBlank()) {
            val refreshed = synchronized(this) {
                // Skip only if ANOTHER thread already swapped in a fresh token while we waited on the
                // lock. We dedup on token IDENTITY, not local validity: a 401 on the token we actually
                // sent must trigger a refresh — that's how a dead refresh token (invalid_grant) gets
                // surfaced and discarded, even when our local expiry clock still thinks it's valid.
                if (encryptedPrefs.accessToken != usedToken && encryptedPrefs.isTokenValid) Result.success(Unit)
                else runBlocking { authManager.refreshAccessToken() }
            }
            if (refreshed.isSuccess) {
                response.close()
                val retryRequest = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${encryptedPrefs.accessToken}")
                    .build()
                return chain.proceed(retryRequest)
            }
        }
        return response
    }
}
