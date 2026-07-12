package com.crsmthw.lyra.data.remote.model

import com.google.gson.annotations.SerializedName

// ── Paging wrapper ───────────────────────────────────────────────────────────
data class Paged<T>(
    val items  : List<T>,
    val total  : Int,
    val limit  : Int,
    val offset : Int,
    val next   : String?,
)

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
    val popularity : Int?                = null,
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

data class ArtistTopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

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
) {
    val primaryArtist  : String  get() = artists?.firstOrNull()?.name ?: "Unknown"
    val primaryArtistId: String? get() = artists?.firstOrNull()?.id
    val allArtists     : String  get() = artists?.joinToString(" · ") { it.name } ?: primaryArtist
    val thumbnailUrl   : String  get() = album?.images?.lastOrNull()?.url ?: ""
    val artUrl         : String  get() = album?.images?.firstOrNull()?.url ?: ""
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
    val product      : String?,           // "premium" | "free"
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

data class DevicesResponse(val devices: List<SpotifyDevice>)

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
data class SearchResponse(
    val tracks    : Paged<SpotifyTrack>?    = null,
    val albums    : Paged<SpotifyAlbum>?    = null,
    val artists   : Paged<SpotifyArtist>?   = null,
    val playlists : Paged<SpotifyPlaylist>? = null,
)

// ── API list wrappers ────────────────────────────────────────────────────────
data class UserPlaylistsResponse(
    val items  : List<SpotifyPlaylist>,
    val total  : Int,
    val next   : String?,
)

data class PlaylistTracksResponse(
    val items  : List<PlaylistTrack>?,
    val total  : Int,
    val next   : String?,
)

data class SavedTracksResponse(
    val items  : List<SavedTrack>?,
    val total  : Int,
    val next   : String?,
)

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
    val items : List<PlayHistoryItem>? = null,
)

// ── Saved albums (Get User's Saved Albums) ───────────────────────────────────
data class SavedAlbum(
    @SerializedName("added_at") val addedAt : String? = null,
    val album : SpotifyAlbum?,   // null-safe: albums can vanish from the catalog
)

data class SavedAlbumsResponse(
    val items : List<SavedAlbum>? = null,
    val total : Int     = 0,
    val next  : String? = null,
)

// ── Followed artists (Get Followed Artists — cursor-paged, nested) ───────────
data class FollowedArtistsResponse(
    val artists : FollowedArtistsPage? = null,
)

data class FollowedArtistsPage(
    val items   : List<SpotifyArtist>? = null,
    val total   : Int = 0,
    val cursors : FollowCursors? = null,
)

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
    val popularity : Int?                    = null,
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
    @SerializedName("currently_playing") val currentlyPlaying: SpotifyTrack?,
    @SerializedName("queue")             val queue            : List<SpotifyTrack> = emptyList(),
)

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
