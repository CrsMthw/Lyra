package com.crsmthw.lyra.ui.ipod.nav

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.crsmthw.lyra.ui.ipod.IPodBodyColor
import com.crsmthw.lyra.ui.ipod.wheel.ClickSoundsConfig

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
    /** Hold the centre button over Now Playing: the Classic's options menu for the playing song (D). */
    data object NowPlayingOptions : IPodScreen
    /** The owned playlists to add [trackUri] to, from the options menu (D). */
    data class AddToPlaylist(val trackUri: String) : IPodScreen
    data object Settings : IPodScreen
}

/*
 * `@Immutable` on the LCD state types: `LcdLabel` is a sealed interface, so without the annotation
 * the Compose compiler infers every class that carries one as UNSTABLE and skipping would rest
 * solely on the ViewModel preserving instances across the 1 Hz progress tick. With it, skipping
 * falls back to equals(). The contract: never mutate one of these after construction.
 */

/**
 * A string the LCD resolves at draw time: a resource for fixed menu text, plain text for data
 * from Spotify. Lets the ViewModel build menus without touching `Resources`.
 */
@Immutable
sealed interface LcdLabel {
    data class Text(val value: String) : LcdLabel
    data class Res(@StringRes val id: Int) : LcdLabel
    /** A format-arg string resource ("%1$d%%", "%1$s left"). */
    data class ResArgs(@StringRes val id: Int, val args: List<Any>) : LcdLabel
    /** A plural resource; [quantity] is also passed as its first format argument. */
    data class Plural(@PluralsRes val id: Int, val quantity: Int) : LcdLabel
    /** Several labels joined by [separator], blanks dropped — "2019 · Album", "<date> · Played". */
    data class Joined(val parts: List<LcdLabel>, val separator: String = " · ") : LcdLabel
}

/** One row of an LCD list. [value] is the right-aligned setting value ("On"/"Off"). */
@Immutable
data class LcdItem(
    val id: String,
    val title: LcdLabel,
    val subtitle: LcdLabel? = null,
    val value: LcdLabel? = null,
    /** Draws the › chevron the Classic shows on rows that open a submenu. */
    val hasSubmenu: Boolean = false,
    /**
     * The row's cover (the track's 640px `artUrl`), read by Cover Flow to draw its tile. Null or
     * blank → the placeholder tile. Menu rows leave it null. (Checkpoint C)
     */
    val artUrl: String? = null,
)

/** Rows the LCD shows for a list of single-line items (menus) and of two-line items (songs). */
const val LCD_ROWS_SINGLE_LINE = 9
const val LCD_ROWS_TWO_LINE = 6

@Immutable
data class LcdListState(
    val items: List<LcdItem> = emptyList(),
    val selectedIndex: Int = 0,
    /**
     * Index of the row at the TOP of the visible window. The ViewModel maintains it with the
     * Classic rule (selection past the bottom row → it becomes the bottom row; above the top → the
     * top row; otherwise unchanged), so the LCD renders the right window in the SAME frame as the
     * selection change (no flash) and a MENU pop restores exactly the window the user left.
     * The LCD never scrolls on its own: it draws rows [firstVisibleIndex, +visibleRows).
     */
    val firstVisibleIndex: Int = 0,
    /** [LCD_ROWS_SINGLE_LINE] or [LCD_ROWS_TWO_LINE], decided by the ViewModel when it builds the list. */
    val visibleRows: Int = LCD_ROWS_SINGLE_LINE,
    val isLoading: Boolean = false,
    val error: LcdLabel? = null,
    /** More pages exist server-side; the ViewModel fetches them as the highlight nears the end. */
    val hasMore: Boolean = false,
)

@Immutable
data class IPodStackEntry(
    val screen: IPodScreen,
    /** The status-bar title while this entry is on top ("iPod", "Music", "Now Playing"…). */
    val title: LcdLabel,
    val list: LcdListState = LcdListState(),
)

/** Which way the LCD content slides on the next change: push = FORWARD (in from the right). */
enum class LcdNavDirection { NONE, FORWARD, BACK }

/** What the Now Playing bottom bar shows and what the wheel drives there; SELECT cycles them. */
enum class NowPlayingMode { SCRUB, VOLUME, SHUFFLE, REPEAT }

/** Repeat as the LCD names it (Spotify's "off" / "context" / "track"). */
enum class LcdRepeat { OFF, ALL, ONE }

/** The now-playing mirror the LCD renders (status-bar indicator + the Now Playing screen). */
@Immutable
data class LcdNowPlaying(
    /** The playing item's uri — the identity a scrub belongs to. */
    val uri: String = "",
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    /** Non-null while the wheel is scrubbing; the bar shows this instead of [progressMs]. */
    val scrubProgressMs: Long? = null,
    /** "N of M" under the album name, known only while the playing track is the one the user picked from a list. */
    val positionInList: Int? = null,
    val listSize: Int? = null,
    /** Which bar the bottom strip shows; SELECT on Now Playing cycles it. Carried across ticks. */
    val mode: NowPlayingMode = NowPlayingMode.SCRUB,
    /** Android media volume, 0..100 — refreshed while the volume bar shows. */
    val volumePercent: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeat: LcdRepeat = LcdRepeat.OFF,
    /**
     * Whether the playing track is in Liked Songs — PlayerViewModel's server-checked `isLiked`,
     * mirrored in by IPodRoot through `IPodViewModel.onPlayerLikedChanged`; null until known, and
     * always null for an episode. Carried across the 1 Hz tick for the same uri only. (D)
     */
    val isLiked: Boolean? = null,
    /** The playing track's album / first artist ids, for the options menu's Go to Album / Artist. (D) */
    val albumId: String? = null,
    val artistId: String? = null,
    /** A podcast episode: no like, no add-to-playlist, no album / artist. (D) */
    val isEpisode: Boolean = false,
)

/**
 * How far the shared liked-songs indexer (`LikedSongsIndexer`) has got: [indexed] rows of the
 * server's [total] are in the cache. The ViewModel sets it only while the index is INCOMPLETE and
 * the total is known; null means "nothing to say" (complete, or unknown). Cover Flow may show it
 * as a quiet "Indexing N of M" line while its list is still growing. (Checkpoint C)
 */
@Immutable
data class LcdIndexStatus(val indexed: Int, val total: Int)

@Immutable
data class IPodUiState(
    /** Never empty; the last entry is showing. */
    val stack: List<IPodStackEntry>,
    val direction: LcdNavDirection = LcdNavDirection.NONE,
    val nowPlaying: LcdNowPlaying? = null,
    val clickSounds: ClickSoundsConfig = ClickSoundsConfig(),
    /** Silver or black body — the iPod's own Settings → Color. */
    val bodyColor: IPodBodyColor = IPodBodyColor.SILVER,
    /** Non-null while the liked-songs index is still filling — see [LcdIndexStatus]. */
    val likedIndex: LcdIndexStatus? = null,
) {
    val current: IPodStackEntry get() = stack.last()
}
