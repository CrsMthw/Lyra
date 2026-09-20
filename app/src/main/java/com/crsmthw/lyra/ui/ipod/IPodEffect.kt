package com.crsmthw.lyra.ui.ipod

/**
 * What the iPod asks the rest of Lyra to do. `IPodViewModel` emits these; `IPodRoot` collects
 * them and routes each to the Activity-scoped `PlayerViewModel`, so the iPod never duplicates
 * `playTrack`'s 404 / App Remote / restore-loop policy. Everything else (menu navigation, the
 * iPod's own settings) stays inside the ViewModel.
 */
sealed interface IPodEffect {
    /**
     * → PlayerViewModel.playTrack(uri, contextUri, uris, index, startPositionMs, shuffle).
     * [shuffle] = false means "the user picked THIS song, so turn shuffle off first" — with shuffle
     * left on, Spotify starts a uris body at a random entry (the "tapped one song, got another"
     * bug). null leaves the device's shuffle state alone; Shuffle Songs is the only path that turns
     * it on, through [ShuffleContext].
     */
    data class PlayTrack(
        val uri: String,
        val contextUri: String? = null,
        val uris: List<String>? = null,
        val index: Int? = null,
        val startPositionMs: Long? = null,
        val shuffle: Boolean? = false,
    ) : IPodEffect

    /** → PlayerViewModel.playFromLikedSongs(uri, shuffle) — the cached-uris path Liked Songs uses. */
    data class PlayLikedSong(val uri: String, val shuffle: Boolean? = false) : IPodEffect

    /** → PlayerViewModel.shuffleContext(contextUri) — shuffle on, then play the context. */
    data class ShuffleContext(val contextUri: String) : IPodEffect

    data object PlayPause : IPodEffect
    data object Next : IPodEffect
    data object Previous : IPodEffect

    /** → PlayerStateManager.setShuffle — the Now Playing shuffle bar (clockwise = on). */
    data class SetShuffle(val enabled: Boolean) : IPodEffect

    /** → PlayerStateManager.setRepeat("off" / "context" / "track") — the Now Playing repeat bar. */
    data class SetRepeat(val state: String) : IPodEffect

    /** → PlayerViewModel.seekTo(fraction) — committed once per scrub, never per detent. */
    data class SeekTo(val fraction: Float) : IPodEffect
}
