package com.crsmthw.lyra.data.auth

import android.util.Log
import com.crsmthw.lyra.data.local.EncryptedPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthorizationException
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

private const val TAG = "TokenManager"

/** The pause before the ONE retry of a refresh that failed on the network (2026-10-04). */
private const val TRANSIENT_REFRESH_RETRY_MS = 1_000L

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
                    refreshWithTransientRetry("proactive")
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
        if (response.code == 401 && encryptedPrefs.refreshToken.isBlank()) {
            Log.d(TAG, "401: no refresh token stored — the 401 goes to the caller")
        } else if (response.code == 401) {
            val refreshed = synchronized(this) {
                // Skip only if ANOTHER thread already swapped in a fresh token while we waited on the
                // lock. We dedup on token IDENTITY, not local validity: a 401 on the token we actually
                // sent must trigger a refresh — that's how a dead refresh token (invalid_grant) gets
                // surfaced and discarded, even when our local expiry clock still thinks it's valid.
                if (encryptedPrefs.accessToken != usedToken && encryptedPrefs.isTokenValid) {
                    Log.d(TAG, "401 refresh: skipped — another request already refreshed the token")
                    Result.success(Unit)
                } else refreshWithTransientRetry("401")
            }
            if (refreshed.isSuccess) {
                response.close()
                val retryRequest = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${encryptedPrefs.accessToken}")
                    .build()
                val retried = chain.proceed(retryRequest)
                // The cold-start question of 2026-10-04 20:26 (a first poll lost to a 401): did the
                // retry carry a token the server took?
                Log.d(TAG, "401 retry of ${chain.request().url.encodedPath} with the refreshed token → HTTP ${retried.code}")
                return retried
            }
            // The refresh's own line says why; the original 401 goes to the caller.
        }
        return response
    }

    /**
     * One token refresh for [trigger] ("proactive" / "401"), retried ONCE after
     * [TRANSIENT_REFRESH_RETRY_MS] when it failed on the NETWORK (2026-10-04, device 20:33: a DNS
     * blip failed the 401 path's refresh and the Library showed Spotify's raw "Missing/invalid/
     * expired access token" — the next poll a minute later was fine). Never retried otherwise: an
     * `invalid_grant` has already discarded the tokens and must not be retried (Spotify's rule),
     * and any other OAuth error would fail the same way again. Called under the interceptor's
     * lock — the waiters re-check validity / token identity, so holding it for the pause is what
     * stops them firing their own refresh meanwhile. One log line per outcome; never a token.
     */
    private fun refreshWithTransientRetry(trigger: String): Result<Unit> = runBlocking {
        val first = authManager.refreshAccessToken()
        val firstError = first.exceptionOrNull()
        if (firstError == null) {
            Log.d(TAG, "$trigger refresh: ok")
            return@runBlocking first
        }
        if (!firstError.isTransientRefreshFailure()) {
            Log.d(TAG, "$trigger refresh: failed — ${firstError.describeRefreshFailure()}; not retried")
            return@runBlocking first
        }
        Log.d(TAG, "$trigger refresh: failed — ${firstError.describeRefreshFailure()}; " +
                   "retrying once in $TRANSIENT_REFRESH_RETRY_MS ms")
        delay(TRANSIENT_REFRESH_RETRY_MS)
        val second = authManager.refreshAccessToken()
        val secondError = second.exceptionOrNull()
        Log.d(TAG, "$trigger refresh retry: " +
                   if (secondError == null) "ok" else "failed — ${secondError.describeRefreshFailure()}; giving up")
        second
    }
}

/**
 * A refresh that failed on the NETWORK, worth one more try: AppAuth wraps the token request's
 * `IOException` (an `UnknownHostException`, a timeout, a reset) as `GeneralErrors.NETWORK_ERROR`
 * with the IOException as its cause (appauth 0.11.1 `TokenRequestTask`). Never `invalid_grant`
 * (TYPE_OAUTH_TOKEN_ERROR — the tokens are already discarded) and never a JSON / OAuth error.
 */
internal fun Throwable.isTransientRefreshFailure(): Boolean =
    this is IOException || cause is IOException ||
    (this is AuthorizationException &&
        type == AuthorizationException.TYPE_GENERAL_ERROR &&
        code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code)

/** The failure's class, AppAuth type/code/error and root cause — what a log line may carry (no token, no request). */
internal fun Throwable.describeRefreshFailure(): String = buildString {
    val self = this@describeRefreshFailure
    append(self.javaClass.simpleName)
    if (self is AuthorizationException) {
        append(" type=").append(self.type).append(" code=").append(self.code)
        self.error?.let { append(" error=").append(it) }
    }
    self.message?.let { append(": ").append(it.take(160)) }
    self.cause?.let { c ->
        append(" (cause ").append(c.javaClass.simpleName)
        c.message?.let { append(": ").append(it.take(160)) }
        append(')')
    }
}
