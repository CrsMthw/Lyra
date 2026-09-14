package com.crsmthw.lyra.data.remote.model

import com.google.gson.annotations.SerializedName

// ── Paging wrapper ───────────────────────────────────────────────────────────
/**
 * One page of a Spotify collection.
 *
 * [rawItems] is the array **exactly as it arrived**, and both of its nullabilities are real: the
 * key can be absent, and individual SLOTS can be `null` for items that are unavailable or removed
 * in the user's market (`"items": [null, {…}]` — observed on podcast episode pages, 2026-09-13).
 * Gson allocates via `Unsafe` and bypasses the Kotlin constructor, so a declared non-null element
 * type buys nothing at runtime: the list really does hold nulls and the first `it.foo` on one is an
 * NPE. Declaring the element type honestly is the only fix that holds. (Nullability is Kotlin
 * metadata — `List<T?>` and `List<T>` are the same JVM type, so Gson, Retrofit and the
 * `-keep class …data.remote.model.**` rule are all unaffected.)
 *
 * Consumers read [items], which is null-free by construction, and page by [rawCount], which counts
 * every slot the endpoint returned. The two differ exactly when a page carried nulls, and
 * conflating them is the raw-offset bug (docs/CACHING.md → `CachedTrackList.rawOffset`): an offset
 * taken from the filtered list re-requests the dropped slots and duplicates the rows after them.
 *
 * [rawCount] only means anything on a freshly parsed page — after a client-side merge (see
 * `SearchViewModel.appendItems`) `rawItems` is already null-free and the two counts coincide.
 *
 * `T : Any` is what lets the element type be filtered; every instantiation is a concrete model.
 */
data class Paged<T : Any>(
    @SerializedName("items") val rawItems: List<T?>? = null,
    val total  : Int     = 0,
    val limit  : Int     = 0,
    val offset : Int     = 0,
    val next   : String? = null,
) {
    /** The page's items with the unavailable slots dropped — what every consumer renders. */
    val items: List<T> get() = rawItems?.filterNotNull() ?: emptyList()

    /** Slots the endpoint returned, nulls included — what an `offset` advances by. */
    val rawCount: Int get() = rawItems?.size ?: 0
}

// ── Image ────────────────────────────────────────────────────────────────────
data class SpotifyImage(
    val url    : String?,
    val height : Int?,
    val width  : Int?,
)

// ── Artist ───────────────────────────────────────────────────────────────────
data class SpotifyArtist(
    val id     : String,
    val name   : String,
    val images : List<SpotifyImage>? = null,
)

data class ArtistFollowers(
    val total : Int = 0,
)

data class SpotifyArtistFull(
    val id         : String,
    val name       : String,
    val images     : List<SpotifyImage>? = null,
    val genres     : List<String>?       = null,
    val followers  : ArtistFollowers?    = null,
    val uri        : String              = "",
) {
    val imageUrl          : String get() = images?.firstOrNull()?.url ?: ""
    val formattedFollowers: String get() {
        val total = followers?.total ?: return ""
        return when {
            total >= 1_000_000 -> "${"%.1f".format(total / 1_000_000f)}M followers"
            total >= 1_000     -> "${total / 1_000}K followers"
            else               -> "$total followers"
        }
    }
}

// ── Album ────────────────────────────────────────────────────────────────────
data class SpotifyAlbum(
    val id     : String,
    val name   : String,
    val images : List<SpotifyImage>?  = null,
    val artists: List<SpotifyArtist>? = null,
    @SerializedName("release_date") val releaseDate : String? = null,
    @SerializedName("album_type")   val albumType   : String? = null,
) {
    val releaseYear: String get() = releaseDate.orEmpty().take(4)
}

// ── Track ────────────────────────────────────────────────────────────────────
/**
 * A playable item. Despite the name this also models a podcast **episode**: `me/player`,
 * `me/player/queue` and `me/player/recently-played` all put whatever is playing in the same `item`
 * slot, and when that is an episode the payload has no `album` and no `artists` — it carries its
 * own [images] plus an embedded [show] instead. Rather than fork the player state, the queue, the
 * widget and the library cache onto a sealed now-playing type, the episode-only keys are parsed
 * here and the DISPLAY-ONLY derived properties below fall back through them.
 *
 * That fallback is safe precisely because [artUrl], [thumbnailUrl], [primaryArtist] and
 * [allArtists] are never used to address the API — every call site is an `AsyncImage` model or a
 * subtitle `Text`. Anything that *does* address the API (liking, add-to-playlist, lyrics, the
 * share url, the artist/album links) must branch on [isEpisode] instead, because those endpoints
 * are track-specific.
 */
data class SpotifyTrack(
    val id          : String,
    val name        : String,
    val uri         : String,
    val artists     : List<SpotifyArtist>? = null,
    val album       : SpotifyAlbum?        = null,
    @SerializedName("duration_ms")  val durationMs  : Long     = 0L,
    @SerializedName("is_local")     val isLocal     : Boolean  = false,
    @SerializedName("explicit")     val explicit    : Boolean  = false,
    @SerializedName("preview_url")  val previewUrl  : String?  = null,
    @SerializedName("is_playable")  val isPlayable  : Boolean? = null,
    // ── Episode-only keys (absent on tracks, hence nullable with defaults) ──
    /** Spotify's own object type: `"track"` or `"episode"`. */
    val type        : String?              = null,
    /** The episode's own artwork — a track's art lives on its [album] instead. */
    val images      : List<SpotifyImage>?  = null,
    /** The episode's parent show, embedded by the player and queue endpoints. */
    val show        : SpotifyShow?         = null,
) {
    /**
     * True when this item is a podcast episode.
     *
     * Derived from the ITEM, never from the player response's `currently_playing_type`: the
     * transfer lock in `PlayerStateManager.fetchPlayerState` deliberately keeps the PREVIOUS item
     * while taking the new scalars, so a flag carried beside the item would desync from it during
     * a device switch. The uri is checked as well as `type` so rows cached before `type` was
     * parsed still resolve correctly.
     */
    val isEpisode      : Boolean get() = type == "episode" || uri.startsWith("spotify:episode:")
    val primaryArtist  : String  get() = artists?.firstOrNull()?.name ?: show?.name ?: "Unknown"
    /** Null for an episode — there is no artist page to navigate to. */
    val primaryArtistId: String? get() = artists?.firstOrNull()?.id
    val allArtists     : String  get() = artists?.joinToString(" · ") { it.name } ?: primaryArtist
    val thumbnailUrl   : String  get() = album?.images?.lastOrNull()?.url
                                         ?: images?.lastOrNull()?.url
                                         ?: show?.images?.lastOrNull()?.url
                                         ?: ""
    val artUrl         : String  get() = album?.images?.firstOrNull()?.url
                                         ?: images?.firstOrNull()?.url
                                         ?: show?.images?.firstOrNull()?.url
                                         ?: ""
    /**
     * The open.spotify.com page for this item — an episode's is `/episode/`, not `/track/`.
     *
     * Null when there is no page to share. Gson allocates through `Unsafe` and bypasses the
     * constructor, so the declared-non-null [id] can still arrive null (Spotify's player returns a
     * local file with `"id": null`), and a local file has no web page even if it did carry one.
     * The safe call on [id] is therefore a REAL runtime check, not the redundant one the compiler
     * sees — and callers must keep their `?.let`, so a share is inert rather than offering
     * `.../track/null`.
     */
    @Suppress("UNNECESSARY_SAFE_CALL")
    val shareUrl       : String? get() =
        if (isLocal) null
        else id?.let { "https://open.spotify.com/${if (isEpisode) "episode" else "track"}/$it" }
}

// ── Saved track wrapper (for liked songs) ───────────────────────────────────
data class SavedTrack(
    @SerializedName("added_at") val addedAt : String,
    val track : SpotifyTrack?,   // null if track was removed from Spotify
)

// ── Playlist ─────────────────────────────────────────────────────────────────
data class SpotifyPlaylist(
    val id            : String,
    val name          : String,
    val description   : String?,
    val images        : List<SpotifyImage>?  = null,
    val uri           : String,
    // Gson bypasses Kotlin's constructor (Unsafe alloc) so non-null types can still arrive null when
    // the JSON omits the field. Spotify editorial/featured playlists return sparse owner objects — a
    // null owner (or null owner.id) previously NPE'd the auto-generated hashCode/equals when Compose
    // computed structural equality on a List<SpotifyPlaylist> during recomposition. Keep both nullable.
    val owner         : PlaylistOwner?       = null,
    val collaborative : Boolean              = false,
    // Spotify renamed this from "tracks" to "items" in the simplified playlist object; keep "tracks"
    // as a legacy fallback so older/other endpoints still parse. Null here = trackCount reads 0.
    @SerializedName(value = "items", alternate = ["tracks"]) val tracksMeta : PlaylistTracksMeta?,
    @SerializedName("snapshot_id") val snapshotId : String?             = null,
) {
    val thumbnailUrl: String get() = images?.firstOrNull()?.url ?: ""
    val trackCount  : Int    get() = tracksMeta?.total ?: 0
}

data class PlaylistOwner(val id: String?, @SerializedName("display_name") val displayName: String?)
data class PlaylistTracksMeta(val total: Int, val href: String?)

// ── Playlist track wrapper ───────────────────────────────────────────────────
data class PlaylistTrack(
    @SerializedName("added_at") val addedAt : String?,
    val track : SpotifyTrack?,   // deprecated in /items endpoint but still populated
    val item  : SpotifyTrack?,   // new field from /playlists/{id}/items
) {
    val resolvedTrack: SpotifyTrack? get() = track ?: item
}

// ── User profile ─────────────────────────────────────────────────────────────
data class SpotifyUser(
    val id           : String,
    @SerializedName("display_name") val displayName : String?,
    val email        : String?,
    val images       : List<SpotifyImage>? = null,
) {
    val avatarUrl: String get() = images?.firstOrNull()?.url ?: ""
}

// ── Player state ─────────────────────────────────────────────────────────────
data class PlayerStateResponse(
    @SerializedName("is_playing")         val isPlaying    : Boolean,
    @SerializedName("progress_ms")        val progressMs   : Long,
    val item           : SpotifyTrack?,
    @SerializedName("shuffle_state")      val shuffleState : Boolean,
    @SerializedName("repeat_state")       val repeatState  : String,  // "off"|"context"|"track"
    val device         : SpotifyDevice?,
)

data class SpotifyDevice(
    val id                                                        : String?  = null,
    val name                                                      : String,
    val type                                                      : String,
    @SerializedName("volume_percent")    val volumePercent        : Int?     = null,
    @SerializedName("is_active")         val isActive             : Boolean,
    @SerializedName("is_restricted")     val isRestricted         : Boolean  = false,
    @SerializedName("is_private_session") val isPrivateSession    : Boolean  = false,
    @SerializedName("supports_volume")   val supportsVolume       : Boolean  = true,
)

/** Same null-tolerant shape as the paged wrappers above — the array here is `devices`, not `items`. */
data class DevicesResponse(
    @SerializedName("devices") val rawDevices: List<SpotifyDevice?>? = null,
) {
    val devices: List<SpotifyDevice> get() = rawDevices?.filterNotNull() ?: emptyList()
}

data class TransferPlaybackRequest(
    @SerializedName("device_ids") val deviceIds: List<String>,
    val play: Boolean? = null,
)

// ── Full playlist (tracks embedded, avoids /tracks sub-endpoint) ─────────────
data class SpotifyPlaylistFull(
    val id     : String,
    val name   : String,
    @SerializedName("tracks") val tracks: PlaylistTracksResponse?,
)

// ── Play request body ────────────────────────────────────────────────────────
data class PlayOffset(
    @SerializedName("uri") val uri: String? = null,
)

data class PlayRequest(
    @SerializedName("uris")        val uris       : List<String>? = null,
    @SerializedName("context_uri") val contextUri : String?       = null,
    @SerializedName("offset")      val offset     : PlayOffset?   = null,
    @SerializedName("position_ms") val positionMs : Long?         = null,
)

// ── Search results ───────────────────────────────────────────────────────────
/**
 * Every bucket is nullable because `search` only returns the ones the request asked for in `type`:
 * a per-type page (`type=show`) carries `shows` alone, and the Search screen's page merge depends
 * on that — see `SearchViewModel.appendPage`.
 *
 * [shows] holds podcast shows, a full search type like any other (`type=show` survived February
 * 2026). It uses the ordinary [Paged] wrapper rather than `Shows.kt`'s null-tolerant `ShowPage`:
 * the documented "200 with an empty page" caveat belongs to the *episode* endpoints, which want a
 * market — a search page has the same shape here as it does for tracks, albums and artists.
 */
data class SearchResponse(
    val tracks    : Paged<SpotifyTrack>?    = null,
    val albums    : Paged<SpotifyAlbum>?    = null,
    val artists   : Paged<SpotifyArtist>?   = null,
    val playlists : Paged<SpotifyPlaylist>? = null,
    val shows     : Paged<SpotifyShow>?     = null,
)

// ── API list wrappers ────────────────────────────────────────────────────────
//
// Every one of these follows [Paged]'s shape for the same reason: `rawItems` is what Gson parsed,
// nulls and all; `items` is the null-free view consumers render; `rawCount` is the number of slots
// the endpoint returned, which is what an `offset` advances by. `items` keeps each wrapper's
// ORIGINAL nullability — a wrapper whose `items` could be absent still answers null for an absent
// key, so "the key wasn't there" stays distinguishable from "the page was empty".

data class UserPlaylistsResponse(
    @SerializedName("items") val rawItems: List<SpotifyPlaylist?>? = null,
    val total  : Int     = 0,
    val next   : String? = null,
) {
    val items   : List<SpotifyPlaylist> get() = rawItems?.filterNotNull() ?: emptyList()
    val rawCount: Int                   get() = rawItems?.size ?: 0
}

data class PlaylistTracksResponse(
    @SerializedName("items") val rawItems: List<PlaylistTrack?>? = null,
    val total  : Int     = 0,
    val next   : String? = null,
) {
    val items   : List<PlaylistTrack>? get() = rawItems?.filterNotNull()
    val rawCount: Int                  get() = rawItems?.size ?: 0
}

data class SavedTracksResponse(
    @SerializedName("items") val rawItems: List<SavedTrack?>? = null,
    val total  : Int     = 0,
    val next   : String? = null,
) {
    val items   : List<SavedTrack>? get() = rawItems?.filterNotNull()
    val rawCount: Int               get() = rawItems?.size ?: 0
}

// ── Recently played (Get Recently Played Tracks) ─────────────────────────────
data class PlayHistoryContext(
    val type : String? = null,   // "playlist" | "album" | "artist" | "show"
    val uri  : String? = null,   // e.g. spotify:playlist:<id>
) {
    /** The bare id from the context uri, or null if the uri is absent/malformed. */
    val contextId: String? get() = uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
}

data class PlayHistoryItem(
    val track : SpotifyTrack?,
    @SerializedName("played_at") val playedAt : String? = null,
    val context : PlayHistoryContext? = null,
)

data class RecentlyPlayedResponse(
    @SerializedName("items") val rawItems: List<PlayHistoryItem?>? = null,
) {
    val items   : List<PlayHistoryItem>? get() = rawItems?.filterNotNull()
    val rawCount: Int                    get() = rawItems?.size ?: 0
}

// ── Saved albums (Get User's Saved Albums) ───────────────────────────────────
data class SavedAlbum(
    @SerializedName("added_at") val addedAt : String? = null,
    val album : SpotifyAlbum?,   // null-safe: albums can vanish from the catalog
)

data class SavedAlbumsResponse(
    @SerializedName("items") val rawItems: List<SavedAlbum?>? = null,
    val total : Int     = 0,
    val next  : String? = null,
) {
    val items   : List<SavedAlbum>? get() = rawItems?.filterNotNull()
    val rawCount: Int               get() = rawItems?.size ?: 0
}

// ── Followed artists (Get Followed Artists — cursor-paged, nested) ───────────
data class FollowedArtistsResponse(
    val artists : FollowedArtistsPage? = null,
)

data class FollowedArtistsPage(
    @SerializedName("items") val rawItems: List<SpotifyArtist?>? = null,
    val total   : Int = 0,
    val cursors : FollowCursors? = null,
) {
    val items   : List<SpotifyArtist>? get() = rawItems?.filterNotNull()
    val rawCount: Int                  get() = rawItems?.size ?: 0
}

data class FollowCursors(val after: String? = null)

// ── Album (full) ─────────────────────────────────────────────────────────────

data class SpotifyCopyright(
    val text : String = "",
    val type : String = "",
)

data class AlbumTrack(
    val id           : String,
    val name         : String,
    val uri          : String,
    val artists      : List<SpotifyArtist>? = null,
    @SerializedName("duration_ms")  val durationMs  : Long     = 0L,
    @SerializedName("explicit")     val explicit    : Boolean  = false,
    @SerializedName("is_playable")  val isPlayable  : Boolean? = null,
    @SerializedName("track_number") val trackNumber : Int      = 0,
    @SerializedName("disc_number")  val discNumber  : Int      = 1,
) {
    val primaryArtist: String get() = artists?.firstOrNull()?.name ?: "Unknown"
    val allArtists   : String get() = artists?.joinToString(" · ") { it.name } ?: primaryArtist
}

data class SpotifyAlbumFull(
    val id         : String,
    val name       : String,
    val images     : List<SpotifyImage>?     = null,
    val artists    : List<SpotifyArtist>?    = null,
    val tracks     : Paged<AlbumTrack>?      = null,
    val copyrights : List<SpotifyCopyright>? = null,
    val label      : String?                 = null,
    @SerializedName("release_date") val releaseDate : String = "",
    @SerializedName("album_type")   val albumType   : String = "",
    @SerializedName("total_tracks") val totalTracks : Int    = 0,
) {
    val artUrl          : String get() = images?.firstOrNull()?.url ?: ""
    val releaseYear     : String get() = releaseDate.take(4)
    val albumTypeDisplay: String get() = albumType.replaceFirstChar { it.uppercaseChar() }
}

// ── Queue ─────────────────────────────────────────────────────────────────────
data class QueueResponse(
    @SerializedName("currently_playing") val currentlyPlaying: SpotifyTrack?           = null,
    @SerializedName("queue")             val rawQueue        : List<SpotifyTrack?>?    = null,
) {
    /** Null-free view — see [Paged]. An unavailable item in the queue arrives as a `null` slot. */
    val queue: List<SpotifyTrack> get() = rawQueue?.filterNotNull() ?: emptyList()
}

// ── Create playlist request ───────────────────────────────────────────────────
data class CreatePlaylistRequest(
    @SerializedName("name")        val name       : String,
    @SerializedName("description") val description: String  = "",
    @SerializedName("public")      val isPublic   : Boolean = false,
)

// ── Add / remove playlist items requests ─────────────────────────────────────
data class AddTracksRequest(
    @SerializedName("uris") val uris: List<String>,
)

data class RemoveItemEntry(
    @SerializedName("uri") val uri: String,
)

data class RemoveItemsRequest(
    @SerializedName("items") val items: List<RemoveItemEntry>,
)

data class SnapshotIdResponse(
    @SerializedName("snapshot_id") val snapshotId: String? = null,
)
