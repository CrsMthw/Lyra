package com.crsmthw.lyra.data.auth

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.crsmthw.lyra.data.local.EncryptedPrefs
import net.openid.appauth.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Handles Spotify OAuth 2.0 PKCE flow via AppAuth.
 *
 * Flow:
 *  1. [buildAuthIntent] → launches browser / Spotify app for user login
 *  2. Browser redirects to com.crsmthw.lyra://callback
 *  3. [handleAuthResponse] exchanges the code for tokens via [exchangeCodeForTokens]
 *  4. Tokens are stored in [EncryptedPrefs] (AES-256-GCM)
 *
 * No client secret is needed – PKCE is the mobile-safe alternative.
 */
class SpotifyAuthManager(
    private val context       : Context,
    private val encryptedPrefs: EncryptedPrefs,
) {

    companion object {
        const val AUTH_ENDPOINT    = "https://accounts.spotify.com/authorize"
        const val TOKEN_ENDPOINT   = "https://accounts.spotify.com/api/token"
        const val REDIRECT_URI     = "com.crsmthw.lyra://callback"

        val SCOPES = listOf(
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-top-read",
            "user-read-recently-played",
            "streaming",
            "app-remote-control",
        )
    }

    private val authService = AuthorizationService(context)

    // ── Step 1: Build and return the intent that opens the auth browser ──────

    fun buildAuthIntent(clientId: String): Intent {
        val config = AuthorizationServiceConfiguration(
            AUTH_ENDPOINT.toUri(),
            TOKEN_ENDPOINT.toUri(),
        )

        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            REDIRECT_URI.toUri(),
        )
            .setScopes(SCOPES)
            .setAdditionalParameters(mapOf("show_dialog" to "true"))
            .apply {
                val verifier  = CodeVerifierUtil.generateRandomCodeVerifier()
                val challenge = CodeVerifierUtil.deriveCodeVerifierChallenge(verifier)
                val method    = CodeVerifierUtil.getCodeVerifierChallengeMethod()
                setCodeVerifier(verifier, challenge, method)
            }
            .build()

        return authService.getAuthorizationRequestIntent(request)
    }

    // ── Step 2: Parse the redirect Intent ───────────────────────────────────

    fun parseAuthResponse(intent: Intent): Pair<AuthorizationResponse?, AuthorizationException?> {
        val response  = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        return Pair(response, exception)
    }

    // ── Step 3: Exchange auth code for tokens ────────────────────────────────

    suspend fun exchangeCodeForTokens(response: AuthorizationResponse): Result<Unit> =
        suspendCoroutine { cont ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResp, ex ->
                when {
                    tokenResp != null -> {
                        encryptedPrefs.saveTokens(
                            access          = tokenResp.accessToken ?: "",
                            refresh         = tokenResp.refreshToken ?: "",
                            expiresInSeconds= 3600L,  // AppAuth uses elapsedRealtime() not epoch; hardcode
                        )
                        cont.resume(Result.success(Unit))
                    }
                    ex != null -> cont.resume(Result.failure(ex))
                    else       -> cont.resume(Result.failure(Exception("Unknown token exchange error")))
                }
            }
        }

    // ── Token refresh ────────────────────────────────────────────────────────

    suspend fun refreshAccessToken(): Result<Unit> {
        val refreshToken = encryptedPrefs.refreshToken
        val clientId     = encryptedPrefs.clientId
        if (refreshToken.isBlank() || clientId.isBlank()) {
            return Result.failure(Exception("No refresh token / client ID available"))
        }

        val config = AuthorizationServiceConfiguration(
            AUTH_ENDPOINT.toUri(),
            TOKEN_ENDPOINT.toUri(),
        )

        val refreshRequest = TokenRequest.Builder(config, clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .build()

        return suspendCoroutine { cont ->
            authService.performTokenRequest(refreshRequest) { tokenResp, ex ->
                when {
                    tokenResp != null -> {
                        encryptedPrefs.saveTokens(
                            access          = tokenResp.accessToken ?: "",
                            refresh         = tokenResp.refreshToken ?: encryptedPrefs.refreshToken,
                            expiresInSeconds= 3600L,
                        )
                        cont.resume(Result.success(Unit))
                    }
                    ex != null -> cont.resume(Result.failure(ex))
                    else       -> cont.resume(Result.failure(Exception("Refresh failed")))
                }
            }
        }
    }

    fun isAuthenticated(): Boolean = encryptedPrefs.isTokenValid || encryptedPrefs.refreshToken.isNotBlank()

    fun logout() {
        encryptedPrefs.clearTokens()
    }

    fun dispose() {
        authService.dispose()
    }
}
