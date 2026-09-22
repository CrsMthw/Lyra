package com.crsmthw.lyra.data.auth

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.crsmthw.lyra.data.local.EncryptedPrefs
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

        /**
         * Populates `resume_point` on episode objects (`shows/{id}`, `shows/{id}/episodes`) — the
         * Played state and the "N left" progress on the show screen, and the App Remote seek.
         *
         * Held out as a constant because the UI has to ask whether the CURRENT grant carries it:
         * it was added on 2026-09-15, and a token authorized before that keeps working untouched —
         * a refresh never widens a grant, so the scope arrives only with a fresh authorization
         * (Settings → Disconnect Spotify → connect). See [hasScope].
         */
        const val SCOPE_READ_PLAYBACK_POSITION = "user-read-playback-position"

        val SCOPES = listOf(
            "user-library-read",
            "user-library-modify",
            "user-follow-read",      // me/following (followed artists list + contains)
            "user-follow-modify",    // PUT/DELETE me/following (artist follow toggle)
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-public",
            "playlist-modify-private",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-top-read",
            "user-read-recently-played",
            SCOPE_READ_PLAYBACK_POSITION,   // episode resume points + the Played state
            "streaming",
            "app-remote-control",
        )
    }

    private val authService = AuthorizationService(context)

    /**
     * Emitted exactly once when a refresh fails because the refresh token is dead
     * (Spotify `invalid_grant` — e.g. the 6-month expiry, or the user revoked access).
     * The tokens have already been discarded by the time this fires; the UI observes
     * this to route the user back through sign-in. Hot, no replay — a one-shot event.
     */
    private val _sessionExpired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST,
    )
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

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
                            // What Spotify says it GRANTED, never what we asked for: a stored
                            // scope must be the server's claim, so the worst case of a response
                            // that omits it is a feature that stays off (and says so) rather than
                            // one that reports itself available and silently returns nothing.
                            scope           = tokenResp.scope,
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
                            // A refresh CANNOT widen a grant. When the response omits `scope` the
                            // stored value stands (null = keep) — treating an omission as "all of
                            // SCOPES" would make every session authorized before a scope was added
                            // claim it within the hour, which is exactly the lie the show screen's
                            // reconnect hint exists to avoid.
                            scope           = tokenResp.scope,
                        )
                        cont.resume(Result.success(Unit))
                    }
                    ex != null -> {
                        // Spotify returns invalid_grant when the refresh token is dead (6-month
                        // expiry / revoked). AppAuth maps that to TYPE_OAUTH_TOKEN_ERROR + INVALID_GRANT
                        // (verified against appauth 0.11.1 sources). Network/transient failures are
                        // TYPE_GENERAL_ERROR, so this check never fires offline — we must NOT log the
                        // user out for being offline. On a dead token: discard it and do NOT retry,
                        // per Spotify's requirement; signal the UI to send the user back to sign-in.
                        val invalidGrant = ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                            ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
                        if (invalidGrant) {
                            encryptedPrefs.clearTokens()
                            _sessionExpired.tryEmit(Unit)
                        }
                        cont.resume(Result.failure(ex))
                    }
                    else       -> cont.resume(Result.failure(Exception("Refresh failed")))
                }
            }
        }
    }

    fun isAuthenticated(): Boolean = encryptedPrefs.isTokenValid || encryptedPrefs.refreshToken.isNotBlank()

    /**
     * Whether the CURRENT grant carries [scope] — read off the scope string the token endpoint
     * returned, so it answers for the token actually in use rather than for [SCOPES].
     *
     * Nothing stored reads as **not granted**: that is every session authorized before the grant
     * was recorded, and a refresh never widens one, so the honest answer for those is "unknown, so
     * no" until the user reconnects. Compared token-by-token rather than by substring — scope
     * identifiers share prefixes, and `contains` would happily match one inside another.
     */
    fun hasScope(scope: String): Boolean =
        encryptedPrefs.grantedScopes.split(' ', '\t', '\n').any { it == scope }

    fun logout() {
        encryptedPrefs.clearTokens()
    }

    fun dispose() {
        authService.dispose()
    }
}
