package com.crsmthw.lyra.ui.navigation

import java.util.Base64

sealed class Screen(val route: String) {
    data object Auth     : Screen("auth")
    data object Library  : Screen("library")
    data object Search   : Screen("search")
    data object Settings : Screen("settings")
    data object Player   : Screen("player")

    data object Queue : Screen("queue")

    data object Stats : Screen("stats")

    // Deep-link to a specific playlist's track list
    data object PlaylistDetail : Screen("playlist/{id}") {
        fun createRoute(id: String) = "playlist/$id"
    }

    data object AlbumDetail : Screen("album/{id}") {
        fun createRoute(id: String) = "album/$id"
    }

    data object ArtistDetail : Screen("artist/{id}") {
        fun createRoute(id: String) = "artist/$id"
    }

    data object ShowDetail : Screen("show/{id}") {
        fun createRoute(id: String) = "show/$id"
    }

    /**
     * The single landing pad for every incoming Spotify link — web URLs, `spotify.link` short
     * links and `spotify:` URIs alike. It carries the RAW url, works out what the link points at
     * and pops itself off the back stack on the way to the real destination.
     *
     * The url travels **base64url-encoded** (`[A-Za-z0-9_-]` only, no padding). Navigation
     * compiles a `{arg}` in a route into `([^/]*?|)` followed by a `($|\?…|#…)` tail, so a
     * literal `/`, `?` or `#` in the value breaks the match outright; percent-encoding would
     * survive that but leaves how many decode passes the value gets as an open question, while
     * base64url is invariant under all of them.
     */
    data object LinkResolver : Screen("deeplink/resolve/{url}") {
        fun createRoute(rawUrl: String): String =
            "deeplink/resolve/" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawUrl.toByteArray(Charsets.UTF_8))

        /** Inverse of [createRoute]. Returns "" for anything that isn't valid base64url — the
         *  resolver then reports it as an unsupported link instead of crashing. */
        fun decodeUrl(encoded: String): String = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
