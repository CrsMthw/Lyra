package com.crsmthw.lyra.ui.ipod.nav

import androidx.annotation.StringRes

/**
 * Every screen the iPod's LCD can show. The back stack is a list of [IPodStackEntry]; MENU pops,
 * SELECT pushes. Checkpoint A wires MainMenu, Music, Settings and a placeholder NowPlaying; the
 * remaining browse screens exist so the menu can push them (an empty list renders as "No <Title>")
 * and Checkpoint B fills them with data.
 */
sealed interface IPodScreen {
    data object MainMenu : IPodScreen
    data object CoverFlow : IPodScreen
    /** Liked songs. */
    data object Music : IPodScreen
    /** Saved albums. */
    data object Albums : IPodScreen
    data class AlbumTracks(val albumId: String, val albumUri: String) : IPodScreen
    /** Followed artists. */
    data object Artists : IPodScreen
    data class ArtistAlbums(val artistId: String) : IPodScreen
    /** Playlists the user owns. */
    data object Playlists : IPodScreen
    data class PlaylistTracks(val playlistId: String, val playlistUri: String) : IPodScreen
    /** Followed shows. */
    data object Podcasts : IPodScreen
    data class ShowEpisodes(val showId: String) : IPodScreen
    data object NowPlaying : IPodScreen
    data object Settings : IPodScreen
}

/**
 * A string the LCD resolves at draw time: a resource for fixed menu text, plain text for data
 * from Spotify. Lets the ViewModel build menus without touching `Resources`.
 */
sealed interface LcdLabel {
    data class Text(val value: String) : LcdLabel
    data class Res(@StringRes val id: Int) : LcdLabel
}

/** One row of an LCD list. [value] is the right-aligned setting value ("On"/"Off"). */
data class LcdItem(
    val id: String,
    val title: LcdLabel,
    val subtitle: LcdLabel? = null,
    val value: LcdLabel? = null,
    /** Draws the › chevron the Classic shows on rows that open a submenu. */
    val hasSubmenu: Boolean = false,
)

data class LcdListState(
    val items: List<LcdItem> = emptyList(),
    val selectedIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: LcdLabel? = null,
    /** More pages exist server-side; the ViewModel fetches them as the highlight nears the end. */
    val hasMore: Boolean = false,
)

data class IPodStackEntry(
    val screen: IPodScreen,
    /** The status-bar title while this entry is on top ("iPod", "Music", "Now Playing"…). */
    val title: LcdLabel,
    val list: LcdListState = LcdListState(),
)

/** Which way the LCD content slides on the next change: push = FORWARD (in from the right). */
enum class LcdNavDirection { NONE, FORWARD, BACK }

/** The now-playing mirror the LCD renders (status-bar indicator + the Now Playing screen). */
data class LcdNowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    /** Non-null while the wheel is scrubbing; the bar shows this instead of [progressMs]. */
    val scrubProgressMs: Long? = null,
)

data class IPodUiState(
    /** Never empty; the last entry is showing. */
    val stack: List<IPodStackEntry>,
    val direction: LcdNavDirection = LcdNavDirection.NONE,
    val nowPlaying: LcdNowPlaying? = null,
    val clickSoundsEnabled: Boolean = true,
) {
    val current: IPodStackEntry get() = stack.last()
}
