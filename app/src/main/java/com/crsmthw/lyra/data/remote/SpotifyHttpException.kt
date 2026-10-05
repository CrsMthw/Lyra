package com.crsmthw.lyra.data.remote

/**
 * The HTTP failure `SpotifyRepository.safeCall` throws (audit 2026-10-04, decision 5): the status
 * as a NUMBER, `Retry-After` kept raw and parsed, the error body, and the Retrofit cause. The
 * message is byte-for-byte what the plain string form used to be — `"HTTP 429: Retry-After=<raw>"`
 * / `"HTTP <code>: <body>"` — so every display and every `Retry-After=` parse of the TEXT still
 * works. Classification goes through [httpStatus] / [isHttp] only: a `message.contains("404")` also
 * matched a Retry-After value, a local port inside a timeout message, or a JSON body.
 */
class SpotifyHttpException(
    val code         : Int,
    /** The `Retry-After` header as sent (a 429), or null. */
    val retryAfterRaw: String? = null,
    /** The error body (not read for a 429 — the header is the information there). */
    val body         : String? = null,
    cause            : Throwable? = null,
) : Exception(messageFor(code, retryAfterRaw, body), cause) {
    /** `Retry-After` in whole seconds when Spotify sent a numeric one. */
    val retryAfterS: Long? get() = retryAfterRaw?.trim()?.toLongOrNull()

    companion object {
        fun messageFor(code: Int, retryAfterRaw: String?, body: String?): String =
            if (code == 429) "HTTP 429: Retry-After=${retryAfterRaw ?: "unknown"}"
            else "HTTP $code: $body"
    }
}

/** The HTTP status of a `safeCall` failure, or null for anything that is not one (no connectivity, a parse error, a cancellation). */
fun Throwable?.httpStatus(): Int? = (this as? SpotifyHttpException)?.code

/** `httpStatus() == code`. */
fun Throwable?.isHttp(code: Int): Boolean = httpStatus() == code

/** `Retry-After` in seconds from a 429, when Spotify sent a numeric one; null otherwise. */
fun Throwable?.retryAfterSeconds(): Long? = (this as? SpotifyHttpException)?.retryAfterS

/** True when a 403's body names the OAuth scope — the only 403 a reconnect can fix. */
fun Throwable?.isInsufficientScope(): Boolean {
    val e = this as? SpotifyHttpException ?: return false
    return e.code == 403 && e.body?.contains("Insufficient client scope", ignoreCase = true) == true
}
