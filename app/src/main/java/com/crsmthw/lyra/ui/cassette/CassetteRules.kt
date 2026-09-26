package com.crsmthw.lyra.ui.cassette

import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.data.remote.model.SpotifyTrack

/*
 * The cassette overlay's PURE rules — no Android, no Compose — so each one is unit-tested
 * (CassetteRulesTest). PlayerScreen / CassetteOverlay / PlayerViewModel call them; none of the
 * decisions below is made anywhere else.
 */

/**
 * May the idle timer run on the full player? All of: the feature on, the visualizer OFF, music
 * PLAYING and NOT WAKING (see below), lyrics not showing, not the docked ≥1200dp pane (its expand button pushes the real
 * route, where the cassette does trigger), no menu / sheet / dialog open, something to show, and
 * Lyra's window FOCUSED — in split-screen or a pop-up window both apps are RESUMED, and a user
 * working only in the other app never touches Lyra, so without this the cassette (and its
 * keep-on and whole-display dim) would come up under them. A focusable popup or dialog of our own
 * (the ButtonGroup overflow menu, a sheet) also takes focus, so this backs up [overlayOpen] too.
 *
 * Never while TalkBack's touch exploration is on: its swipes and double-taps arrive as
 * accessibility actions, not pointer events, so the idle clock would never be stamped and the
 * cassette would keep covering a player the user is actively navigating.
 *
 * `waking` is the play button's spinner (`PlayerUiState.isWakingUp`): a wake restore after
 * Spotify died marks the state PLAYING optimistically the moment the request goes out, and the
 * spinner stays until the poll reports the item playing. The idle clock must not run under it —
 * the cassette would slide over a player that has not started (Cris, 2026-09-26) — so the
 * countdown arms only once the spinner is gone and the music is actually playing.
 *
 * Only the ENTRY is gated by this. Once the cassette is up, pausing keeps it up with frozen hubs
 * (Cris, 2026-09-23); the overlay's own exits are listed on PlayerScreen's gate.
 */
internal fun cassetteEligible(
    settings         : CassetteSettings,
    isPlaying        : Boolean,
    waking           : Boolean,
    visualizerEnabled: Boolean,
    lyricsShowing    : Boolean,
    docked           : Boolean,
    overlayOpen      : Boolean,
    hasTrack         : Boolean,
    windowFocused    : Boolean,
    touchExploring   : Boolean,
): Boolean = settings.enabled && !visualizerEnabled && isPlaying && !waking && !lyricsShowing &&
    !docked && !overlayOpen && hasTrack && windowFocused && !touchExploring

/*
 * BACK while the cassette is up: every back that reaches Lyra EXITS — there is no in-app "reveal the
 * bars first" step. In gesture navigation with the bars hidden (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
 * the SYSTEM already consumes the first edge swipe to show the bars transiently and dispatches no back
 * event, so "first swipe reveals, second exits" is the platform's own immersive behaviour; a second
 * in-app reveal made it THREE swipes on the Fold 8 (Cris, device pass 2026-09-23). In 3-button
 * navigation the bar had to be revealed to tap back at all. So the overlay's BackHandler is a plain
 * exit, and no navigation-mode detection exists any more.
 */

/** The two album fields the label prints that the player's item does not carry. */
internal data class CassetteAlbumMeta(val copyright: String?, val label: String?)

/**
 * Album → the label's fine print. The "C" (copyright) line wins over the "P" (phonogram) one;
 * blank text is no text, so a blank "C" line falls back to the first non-blank line of any type.
 * Gson allocates through `Unsafe`, so the list may hold null SLOTS and each entry's
 * declared-non-null `text` / `type` may still arrive null — both are read as nullable here.
 */
internal fun cassetteAlbumMetaFrom(album: SpotifyAlbumFull): CassetteAlbumMeta {
    // (type, text) pairs with blank text dropped — read as nullable, see above.
    val lines = album.copyrights.orEmpty().filterNotNull().mapNotNull { c ->
        val type: String? = c.type
        val text: String? = c.text
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { type to it }
    }
    val label: String? = album.label
    return CassetteAlbumMeta(
        copyright = lines.firstOrNull { it.first == "C" }?.second ?: lines.firstOrNull()?.second,
        label     = label?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * The album id to look the fine print up by, or null when there is nothing to look up: a podcast
 * episode (no album), a local file (no Spotify album page), or an album without an id.
 */
internal fun cassetteAlbumIdFor(track: SpotifyTrack): String? {
    if (track.isEpisode || track.isLocal) return null
    val id: String? = track.album?.id
    return id?.takeIf { it.isNotBlank() }
}

/**
 * The now-playing item → what the label prints. A podcast episode puts its SHOW in the artist
 * slot (and has no album or year); the year is printed only as four digits. Every string is read
 * as nullable first — the item is Gson-built, so a declared-non-null `name` can still be null.
 */
internal fun cassetteLabelFor(track: SpotifyTrack, meta: CassetteAlbumMeta?): CassetteLabel {
    val episode = track.isEpisode
    val album = if (episode) null else track.album
    val albumName: String? = album?.name
    val year = album?.releaseYear?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    return CassetteLabel(
        title       = track.name.orEmpty(),
        artist      = if (episode) track.show?.name.orEmpty() else track.allArtists,
        album       = albumName?.takeIf { it.isNotBlank() },
        year        = year,
        copyright   = meta?.copyright,
        recordLabel = meta?.label,
    )
}
