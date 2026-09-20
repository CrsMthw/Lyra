package com.crsmthw.lyra.ui.ipod

/**
 * What the iPod asks the rest of Lyra to do. `IPodViewModel` emits these; `IPodRoot` collects
 * them and routes each to the Activity-scoped `PlayerViewModel`, so the iPod never duplicates
 * `playTrack`'s 404 / App Remote / restore-loop policy. Everything else (menu navigation, the
 * iPod's own settings) stays inside the ViewModel.
 */
sealed interface IPodEffect {
    /** → PlayerViewModel.playTrack(uri, contextUri, uris, index, startPositionMs). */
    data class PlayTrack(
        val uri: String,
        val contextUri: String? = null,
        val uris: List<String>? = null,
        val index: Int? = null,
        val startPositionMs: Long? = null,
    ) : IPodEffect

    /** → PlayerViewModel.playFromLikedSongs(uri) — the cached-uris path Liked Songs uses. */
    data class PlayLikedSong(val uri: String) : IPodEffect

    /** → PlayerViewModel.shuffleContext(contextUri) — shuffle on, then play the context. */
    data class ShuffleContext(val contextUri: String) : IPodEffect

    data object PlayPause : IPodEffect
    data object Next : IPodEffect
    data object Previous : IPodEffect

    /** → PlayerViewModel.seekTo(fraction) — committed once per scrub, never per detent. */
    data class SeekTo(val fraction: Float) : IPodEffect
}
