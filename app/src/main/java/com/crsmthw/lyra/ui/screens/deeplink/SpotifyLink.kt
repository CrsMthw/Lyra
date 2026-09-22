package com.crsmthw.lyra.ui.screens.deeplink

import android.net.Uri
import androidx.core.net.toUri

/**
 * The kinds of Spotify link Lyra can open. The [segment] is both the path segment in a web URL
 * (`open.spotify.com/<segment>/<id>`) and the type in a Spotify URI (`spotify:<segment>:<id>`),
 * which is why one enum covers both forms.
 */
enum class SpotifyLinkType(val segment: String) {
    TRACK    ("track"),
    ALBUM    ("album"),
    ARTIST   ("artist"),
    SHOW     ("show"),
    EPISODE  ("episode"),
    PLAYLIST ("playlist");

    companion object {
        fun fromSegment(segment: String): SpotifyLinkType? =
            entries.firstOrNull { it.segment == segment.lowercase() }
    }
}

/** A normalised Spotify link: a type and a bare id, with every locale segment, query and
 *  fragment already stripped. */
data class SpotifyLink(val type: SpotifyLinkType, val id: String) {
    /** The Spotify URI form — what the Web API's `me/player/play` and the App Remote SDK take. */
    val uri: String get() = "spotify:${type.segment}:$id"
}

/** Hosts whose paths carry a type + id directly. A redirect chain is followed until it lands on
 *  one of these. */
internal val SPOTIFY_WEB_HOSTS = setOf(
    "open.spotify.com",
    "play.spotify.com",
    "spotify.com",
    "www.spotify.com",
)

/** Branch short-link hosts. These carry an opaque code, so they can only be resolved over the
 *  network — see `LinkResolverViewModel`. */
internal val SPOTIFY_SHORT_LINK_HOSTS = setOf(
    "spotify.link",
    "spotify.app.link",
    "spotify-alternate.app.link",
)

/** Spotify ids are base62. Deliberately not length-pinned — the id only has to be well-formed
 *  enough that the API call it feeds is not nonsense. */
private val ID_PATTERN = Regex("[A-Za-z0-9]+")

/** Spotify's web locale segment, e.g. the `intl-de` in `open.spotify.com/intl-de/show/<id>`. */
private val LOCALE_SEGMENT_PATTERN = Regex("intl-[A-Za-z0-9_-]+")

/**
 * A web URL for any supported type, in either the plain or the locale form, with an optional
 * `?si=` tail. Used to dig an `open.spotify.com` URL out of a Branch interstitial's HTML
 * (`og:url` / a canonical link / a JS redirect) when the redirect chain itself doesn't leave
 * their host.
 */
internal val SPOTIFY_WEB_URL_PATTERN = Regex(
    """https://open\.spotify\.com/(?:intl-[A-Za-z0-9_-]+/)?""" +
        """(?:track|album|artist|show|episode|playlist)/[A-Za-z0-9]+"""
)

/**
 * Normalise any shape of Spotify link into a [SpotifyLink], or null when it is not one Lyra
 * recognises at all (a search URL, a user profile, a malformed id, …).
 *
 * Handles:
 *  - `https://open.spotify.com/show/<id>?si=…`  (query and fragment are dropped)
 *  - `https://open.spotify.com/intl-de/show/<id>` (the locale segment is dropped)
 *  - `spotify:show:<id>` — an OPAQUE URI, so the scheme-specific part is split by hand
 *
 * Not handled here: `spotify.link` short links, which have to be followed over the network first.
 */
fun parseSpotifyLink(raw: String): SpotifyLink? {
    val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
    return if (uri.scheme.equals("spotify", ignoreCase = true)) parseUriForm(uri.schemeSpecificPart)
    else parseWebForm(uri)
}

/**
 * `spotify:track:<id>`, and the legacy `spotify:user:<user>:playlist:<id>`. Scanned from the END
 * so the legacy form resolves to the trailing pair rather than to the `user` prefix.
 */
private fun parseUriForm(schemeSpecificPart: String?): SpotifyLink? {
    val parts = schemeSpecificPart?.split(':')?.filter { it.isNotEmpty() } ?: return null
    for (i in parts.lastIndex - 1 downTo 0) {
        val type = SpotifyLinkType.fromSegment(parts[i]) ?: continue
        val id   = parts[i + 1]
        if (ID_PATTERN.matches(id)) return SpotifyLink(type, id)
    }
    return null
}

private fun parseWebForm(uri: Uri): SpotifyLink? {
    val host = uri.host?.lowercase() ?: return null
    if (host !in SPOTIFY_WEB_HOSTS) return null
    // pathSegments already excludes the query and the fragment.
    val segments = uri.pathSegments.filter { it.isNotEmpty() }
    val start    = if (LOCALE_SEGMENT_PATTERN.matches(segments.firstOrNull().orEmpty())) 1 else 0
    val type     = SpotifyLinkType.fromSegment(segments.getOrNull(start).orEmpty()) ?: return null
    val id       = segments.getOrNull(start + 1) ?: return null
    return if (ID_PATTERN.matches(id)) SpotifyLink(type, id) else null
}
