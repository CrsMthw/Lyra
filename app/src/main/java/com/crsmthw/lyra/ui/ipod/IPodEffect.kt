package com.crsmthw.lyra.ui.ipod

import com.crsmthw.lyra.data.remote.model.SpotifyTrack

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

    /**
     * → the app's ONE like path for [uri] (save / remove on the server + the liked-songs cache
     * patch, as PlayerViewModel.toggleLike does). [liked] is the TARGET state. Checkpoint D: the
     * options menu's "Add to / Remove from Liked Songs" row. Never for an episode.
     */
    data class SetLiked(
        val uri: String,
        val liked: Boolean,
        /**
         * The track itself when the iPod has it (a liked-list pick, or the mirrored current track),
         * so the liked-songs cache patch can run even while the player's own currentTrack has not
         * caught up with the picked song (the optimistic window). Null → the shell resolves it.
         */
        val track: SpotifyTrack? = null,
    ) : IPodEffect

    /**
     * → the app's add-to-playlist path (POST playlists/{id}/items + the cached track-list patch +
     * the server-count reconcile that every in-app mutation triggers). Checkpoint D: the options
     * menu's "Add to Playlist" → owned-playlist row. Never for an episode.
     */
    data class AddToPlaylist(
        val playlistId: String,
        val trackUri: String,
        /** The playlist's count as its row showed it — the cached-list append's completeness guard needs it. */
        val trackCount: Int? = null,
        /** The track itself when the iPod has it (see [SetLiked.track]) — the cached row append needs the full object. */
        val track: SpotifyTrack? = null,
    ) : IPodEffect

    /**
     * → PlayerViewModel.setSleepTimer(minutes) — the options menu's Sleep Timer row, which cycles
     * Lyra's own steps (Off · 5 · 15 · 30 · 45 · 60, as the player's dialog offers). 0 = off. (D)
     */
    data class SetSleepTimer(val minutes: Int) : IPodEffect
}
