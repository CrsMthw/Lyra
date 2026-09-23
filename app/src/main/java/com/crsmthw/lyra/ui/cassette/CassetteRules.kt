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
 * PLAYING, lyrics not showing, not the docked ≥1200dp pane (its expand button pushes the real
 * route, where the cassette does trigger), no menu / sheet / dialog open, and something to show.
 *
 * Only the ENTRY is gated by this. Once the cassette is up, pausing keeps it up with frozen hubs
 * (Cris, 2026-09-23); the overlay's own exits are listed on PlayerScreen's gate.
 */
internal fun cassetteEligible(
    settings         : CassetteSettings,
    isPlaying        : Boolean,
    visualizerEnabled: Boolean,
    lyricsShowing    : Boolean,
    docked           : Boolean,
    overlayOpen      : Boolean,
    hasTrack         : Boolean,
): Boolean = settings.enabled && !visualizerEnabled && isPlaying && !lyricsShowing &&
    !docked && !overlayOpen && hasTrack

/** What a back press does while the cassette is up. */
internal enum class CassetteBackAction { EXIT, REVEAL_BARS }

/**
 * The back rule (Cris, 2026-09-23). Gesture navigation: the first back only brings the system
 * bars back, the second exits. Three-button (or two-button) navigation: any back exits — the bar
 * had to be revealed for the user to tap back at all, so the reveal has already happened.
 */
internal fun cassetteBackAction(gestureNav: Boolean, barsVisible: Boolean): CassetteBackAction =
    when {
        !gestureNav -> CassetteBackAction.EXIT
        barsVisible -> CassetteBackAction.EXIT
        else        -> CassetteBackAction.REVEAL_BARS
    }

/**
 * `Settings.Secure.NAVIGATION_MODE`'s value → is it gesture navigation? 0 = three-button,
 * 1 = two-button, 2 = fully gestural. Anything unknown (the read failed, an OEM value) is treated
 * as gesture, the safer side: a back then reveals the bars first instead of exiting at once.
 */
internal fun isGestureNavMode(navigationMode: Int?): Boolean = navigationMode != 0 && navigationMode != 1

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
