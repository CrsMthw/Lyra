package com.crsmthw.lyra.ui.navigation

sealed class Screen(val route: String) {
    data object Auth     : Screen("auth")
    data object Library  : Screen("library")
    data object Search   : Screen("search")
    data object Settings : Screen("settings")
    data object Player   : Screen("player")

    data object Queue : Screen("queue")

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

    data object TrackDeepLink : Screen("deeplink/track/{id}") {
        fun createRoute(id: String) = "deeplink/track/$id"
    }
}
