package com.crsmthw.lyra.data.auth

import net.openid.appauth.AuthorizationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which refresh failures TokenManager retries once (2026-10-04): a NETWORK failure only — never
 * `invalid_grant` (the tokens are already discarded; Spotify forbids the retry) and never an OAuth or
 * JSON error. The exceptions are built the way appauth 0.11.1's `TokenRequestTask` builds them
 * (`fromTemplate(GeneralErrors.NETWORK_ERROR, ioException)` / `fromOAuthTemplate(...)`).
 */
class TransientRefreshFailureTest {

    @Test
    fun `a DNS failure wrapped as NETWORK_ERROR is transient`() {
        val ex = AuthorizationException.fromTemplate(
            AuthorizationException.GeneralErrors.NETWORK_ERROR,
            UnknownHostException("Unable to resolve host \"accounts.spotify.com\""),
        )
        assertTrue(ex.isTransientRefreshFailure())
    }

    @Test
    fun `a timeout wrapped as NETWORK_ERROR is transient`() {
        val ex = AuthorizationException.fromTemplate(
            AuthorizationException.GeneralErrors.NETWORK_ERROR,
            SocketTimeoutException("timeout"),
        )
        assertTrue(ex.isTransientRefreshFailure())
    }

    @Test
    fun `a bare IOException is transient`() {
        assertTrue(IOException("reset").isTransientRefreshFailure())
    }

    @Test
    fun `invalid_grant is never transient`() {
        val ex = AuthorizationException.fromOAuthTemplate(
            AuthorizationException.TokenRequestErrors.INVALID_GRANT,
            "invalid_grant",
            "Refresh token revoked",
            null,
        )
        assertFalse(ex.isTransientRefreshFailure())
    }

    @Test
    fun `a JSON deserialization error is not transient`() {
        val ex = AuthorizationException.fromTemplate(
            AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR,
            org.json.JSONException("bad"),
        )
        assertFalse(ex.isTransientRefreshFailure())
    }

    @Test
    fun `the no-refresh-token failure is not transient`() {
        assertFalse(Exception("No refresh token / client ID available").isTransientRefreshFailure())
    }
}
