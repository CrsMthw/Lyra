package com.crsmthw.lyra.data.remote

import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The typed HTTP failure (audit 2026-10-04, decision 5): the status is a number, the message text is unchanged. */
class SpotifyHttpExceptionTest {

    @Test
    fun `a 429 keeps the old message text and parses Retry-After`() {
        val e = SpotifyHttpException(429, retryAfterRaw = "14040")
        assertEquals("HTTP 429: Retry-After=14040", e.message)
        assertEquals(429, e.httpStatus())
        assertEquals(14040L, e.retryAfterSeconds())
        assertTrue(e.isHttp(429))
        assertFalse(e.isHttp(404))
    }

    @Test
    fun `a 429 without a numeric header reads unknown and no seconds`() {
        assertEquals("HTTP 429: Retry-After=unknown", SpotifyHttpException(429).message)
        assertNull(SpotifyHttpException(429, retryAfterRaw = "Wed, 21 Oct 2026 07:28:00 GMT").retryAfterSeconds())
    }

    @Test
    fun `other statuses keep the HTTP code body text`() {
        val e = SpotifyHttpException(404, body = """{"error":{"status":404,"reason":"NO_ACTIVE_DEVICE"}}""")
        assertEquals("""HTTP 404: {"error":{"status":404,"reason":"NO_ACTIVE_DEVICE"}}""", e.message)
        assertEquals(404, e.httpStatus())
    }

    @Test
    fun `a Retry-After containing 404 is a 429, not no-active-device`() {
        val e = SpotifyHttpException(429, retryAfterRaw = "1404")
        assertTrue(e.isHttp(429))
        assertFalse(e.isHttp(404))
    }

    @Test
    fun `a timeout whose local port contains 429 has no status`() {
        val e = SocketTimeoutException("failed to connect to api.spotify.com/35.186.224.25 (port 443) from /192.168.1.5 (port 40429) after 15000ms")
        assertNull(e.httpStatus())
        assertFalse(e.isHttp(429))
        assertNull(e.retryAfterSeconds())
        assertNull((null as Throwable?).httpStatus())
    }

    @Test
    fun `a plain exception carrying HTTP text is not a status any more`() {
        assertNull(Exception("HTTP 404: something").httpStatus())
    }

    @Test
    fun `insufficient scope is a 403 whose body says so`() {
        assertTrue(SpotifyHttpException(403, body = """{"error":{"status":403,"message":"Insufficient client scope"}}""").isInsufficientScope())
        assertFalse(SpotifyHttpException(403, body = """{"error":{"status":403,"message":"Forbidden"}}""").isInsufficientScope())
        assertFalse(SpotifyHttpException(429, retryAfterRaw = "1403").isInsufficientScope())
        assertFalse(Exception("403 Insufficient client scope").isInsufficientScope())
    }
}
