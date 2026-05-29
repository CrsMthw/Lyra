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

// ── Album ────────────────────────────────────────────────────────────────────
data class SpotifyAlbum(
    val id     : String,
    val name   : String,
    val images : List<SpotifyImage>?  = null,
    val artists: List<SpotifyArtist>? = null,
    @SerializedName("release_date") val releaseDate: String = "",
)

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
    val primaryArtist: String get() = artists?.firstOrNull()?.name ?: "Unknown"
    val thumbnailUrl : String get() = album?.images?.lastOrNull()?.url ?: ""
    val artUrl       : String get() = album?.images?.firstOrNull()?.url ?: ""
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
    val owner         : PlaylistOwner,
    val collaborative : Boolean              = false,
    @SerializedName("tracks")      val tracksMeta : PlaylistTracksMeta?,
    @SerializedName("snapshot_id") val snapshotId : String?             = null,
) {
    val thumbnailUrl: String get() = images?.firstOrNull()?.url ?: ""
    val trackCount  : Int    get() = tracksMeta?.total ?: 0
}

data class PlaylistOwner(val id: String, @SerializedName("display_name") val displayName: String?)
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
    val id             : String,
    val name           : String,
    val type           : String,
    @SerializedName("volume_percent") val volumePercent: Int,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("is_restricted") val isRestricted: Boolean = false,
)

data class DevicesResponse(val devices: List<SpotifyDevice>)

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

data class FeaturedPlaylistsResponse(
    val message  : String?,
    val playlists: UserPlaylistsResponse,
)

// ── Queue ─────────────────────────────────────────────────────────────────────
data class QueueResponse(
    @SerializedName("currently_playing") val currentlyPlaying: SpotifyTrack?,
    @SerializedName("queue")             val queue            : List<SpotifyTrack> = emptyList(),
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
