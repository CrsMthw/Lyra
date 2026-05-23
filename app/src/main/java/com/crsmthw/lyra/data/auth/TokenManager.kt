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

        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${encryptedPrefs.accessToken}")
            .build()

        val response = chain.proceed(request)

        // 401 = token expired mid-flight despite our check; retry once after refresh
        return if (response.code == 401) {
            response.close()
            runBlocking { authManager.refreshAccessToken() }
            val retryRequest = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${encryptedPrefs.accessToken}")
                .build()
            chain.proceed(retryRequest)
        } else {
            response
        }
    }
}
