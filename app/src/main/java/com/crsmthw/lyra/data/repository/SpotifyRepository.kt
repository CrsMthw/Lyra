package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Single source of truth for all Spotify data.
 * Returns [Result] so ViewModels never have to catch.
 */
class SpotifyRepository(
    private val api           : SpotifyApiService,
    private val encryptedPrefs: EncryptedPrefs,
) {

    suspend fun getCurrentUser(): Result<SpotifyUser> = safeCall {
        api.getCurrentUser()
    }

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): Result<UserPlaylistsResponse> = safeCall {
        api.getUserPlaylists(limit, offset)
    }

    suspend fun getLikedSongs(limit: Int = 50, offset: Int = 0): Result<SavedTracksResponse> = safeCall {
        api.getLikedSongs(limit, offset)
    }

    suspend fun getAllLikedSongs(): Result<List<SpotifyTrack>> = safeCall {
        val all    = mutableListOf<SpotifyTrack>()
        var offset = 0
        val limit  = 50
        while (true) {
            val page = api.getLikedSongs(limit, offset)
            all.addAll((page.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false })
            if (page.next == null || all.size >= page.total) break
            offset += limit
        }
        all
    }

    suspend fun getPlaylistTracks(id: String, limit: Int = 50, offset: Int = 0): Result<PlaylistTracksResponse> = safeCall {
        if (offset == 0) {
            try {
                val full = api.getPlaylistFull(id)
                val embedded = full.tracks
                if (embedded != null) return@safeCall embedded
            } catch (_: Exception) { }
        }
        api.getPlaylistTracks(id, limit, offset)
    }

    suspend fun getFeaturedPlaylists(): Result<FeaturedPlaylistsResponse> = safeCall {
        api.getFeaturedPlaylists()
    }

    suspend fun getAlbum(id: String): Result<SpotifyAlbumFull> = safeCall {
        api.getAlbum(id)
    }

    suspend fun getArtist(id: String): Result<SpotifyArtistFull> = safeCall {
        api.getArtist(id)
    }

    suspend fun getArtistAlbums(id: String, offset: Int = 0): Result<Paged<SpotifyAlbum>> = safeCall {
        api.getArtistAlbums(id, offset = offset)
    }

    suspend fun search(query: String): Result<SearchResponse> = safeCall {
        api.search(query = query, type = "track,album,artist", limit = 10)
    }

    suspend fun saveTrack(trackId: String): Result<Unit> = safeCall {
        api.saveTracks("spotify:track:$trackId")
    }

    suspend fun removeTrack(trackId: String): Result<Unit> = safeCall {
        api.removeTracks("spotify:track:$trackId")
    }

    suspend fun isTrackSaved(trackId: String): Result<Boolean> = safeCall {
        api.checkSavedTracks("spotify:track:$trackId").firstOrNull() ?: false
    }

    suspend fun getPlayerState(): Result<PlayerStateResponse?> = safeCall {
        api.getPlayerState()
    }

    suspend fun play(
        uri        : String?       = null,
        contextUri : String?       = null,
        offsetUri  : String?       = null,
        uris       : List<String>? = null,
        positionMs : Long?         = null,
    ): Result<Unit> = safeCall {
        when {
            contextUri != null -> api.play(PlayRequest(
                contextUri = contextUri,
                offset     = offsetUri?.let { PlayOffset(uri = it) },
                positionMs = positionMs,
            ))
            uris != null -> api.play(PlayRequest(uris = uris, positionMs = positionMs))
            uri  != null -> api.play(PlayRequest(uris = listOf(uri), positionMs = positionMs))
            else         -> api.resumePlayback()
        }
    }

    suspend fun createPlaylist(
        name       : String,
        description: String,
        isPublic   : Boolean,
    ): Result<SpotifyPlaylist> = safeCall {
        api.createPlaylist(CreatePlaylistRequest(name, description, isPublic))
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackUri: String): Result<Unit> = safeCall {
        api.addTracksToPlaylist(playlistId, AddTracksRequest(listOf(trackUri)))
        Unit
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackUri: String): Result<Unit> = safeCall {
        api.removeItemsFromPlaylist(playlistId, RemoveItemsRequest(listOf(RemoveItemEntry(trackUri))))
        Unit
    }

    suspend fun getQueue(): Result<QueueResponse?> = safeCall {
        api.getQueue()
    }

    suspend fun getAvailableDevices(): Result<List<SpotifyDevice>> = safeCall {
        api.getAvailableDevices()?.devices ?: emptyList()
    }

    suspend fun transferPlayback(deviceId: String): Result<Unit> = safeCall {
        api.transferPlayback(TransferPlaybackRequest(deviceIds = listOf(deviceId), play = true))
    }

    suspend fun pause(): Result<Unit>                          = safeCall { api.pause() }
    suspend fun skipNext(): Result<Unit>                       = safeCall { api.skipNext() }
    suspend fun skipPrevious(): Result<Unit>                   = safeCall { api.skipPrevious() }
    suspend fun seek(positionMs: Long): Result<Unit>           = safeCall { api.seek(positionMs) }
    suspend fun setShuffle(state: Boolean): Result<Unit>       = safeCall { api.setShuffle(state) }
    suspend fun setRepeat(state: String): Result<Unit>         = safeCall { api.setRepeat(state) }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
                    block()
                } catch (e: HttpException) {
                    when (e.code()) {
                        429  -> throw Exception("HTTP 429: Retry-After=${e.response()?.headers()?.get("Retry-After") ?: "unknown"}")
                        else -> throw Exception("HTTP ${e.code()}: ${e.response()?.errorBody()?.string()}")
                    }
                }
            }
        }
}
