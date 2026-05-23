package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for all Spotify data.
 * Returns [Result] so ViewModels never have to catch.
 */
class SpotifyRepository(
    private val api           : SpotifyApiService,
    private val encryptedPrefs: EncryptedPrefs,
) {

    suspend fun getCurrentUser(): Result<SpotifyUser> = safeCall {
        api.getCurrentUser().bodyOrThrow()
    }

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): Result<UserPlaylistsResponse> = safeCall {
        api.getUserPlaylists(limit, offset).bodyOrThrow()
    }

    suspend fun getLikedSongs(limit: Int = 50, offset: Int = 0): Result<SavedTracksResponse> = safeCall {
        api.getLikedSongs(limit, offset).bodyOrThrow()
    }

    suspend fun getAllLikedSongs(): Result<List<SpotifyTrack>> = safeCall {
        val all    = mutableListOf<SpotifyTrack>()
        var offset = 0
        val limit  = 50
        while (true) {
            val page = api.getLikedSongs(limit, offset).bodyOrThrow()
            all.addAll((page.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false })
            if (page.next == null || all.size >= page.total) break
            offset += limit
        }
        all
    }

    suspend fun getPlaylistTracks(id: String, limit: Int = 50, offset: Int = 0): Result<PlaylistTracksResponse> = safeCall {
        if (offset == 0) {
            val fullResp = api.getPlaylistFull(id)
            if (fullResp.isSuccessful) {
                val embedded = fullResp.body()?.tracks
                if (embedded != null) return@safeCall embedded
            }
        }
        api.getPlaylistTracks(id, limit, offset).bodyOrThrow()
    }

    suspend fun getFeaturedPlaylists(): Result<FeaturedPlaylistsResponse> = safeCall {
        api.getFeaturedPlaylists().bodyOrThrow()
    }

    suspend fun search(query: String): Result<SearchResponse> = safeCall {
        api.search(query = query, type = "track", limit = 10).bodyOrThrow()
    }

    suspend fun saveTrack(trackId: String): Result<Unit> = safeCall {
        api.saveTracks("spotify:track:$trackId").bodyOrThrow()
    }

    suspend fun removeTrack(trackId: String): Result<Unit> = safeCall {
        api.removeTracks("spotify:track:$trackId").bodyOrThrow()
    }

    suspend fun isTrackSaved(trackId: String): Result<Boolean> = safeCall {
        api.checkSavedTracks("spotify:track:$trackId").bodyOrThrow().firstOrNull() ?: false
    }

    suspend fun getPlayerState(): Result<PlayerStateResponse?> = safeCall {
        val response = api.getPlayerState()
        // 204 = no active device; that's fine, not an error
        if (response.code() == 204) null else response.bodyOrThrow()
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
            )).bodyOrThrow()
            uris != null -> api.play(PlayRequest(uris = uris, positionMs = positionMs)).bodyOrThrow()
            uri  != null -> api.play(PlayRequest(uris = listOf(uri), positionMs = positionMs)).bodyOrThrow()
            else         -> api.resumePlayback().bodyOrThrow()
        }
    }

    suspend fun pause(): Result<Unit>                          = safeCall { api.pause().bodyOrThrow() }
    suspend fun skipNext(): Result<Unit>                       = safeCall { api.skipNext().bodyOrThrow() }
    suspend fun skipPrevious(): Result<Unit>                   = safeCall { api.skipPrevious().bodyOrThrow() }
    suspend fun seek(positionMs: Long): Result<Unit>           = safeCall { api.seek(positionMs).bodyOrThrow() }
    suspend fun setShuffle(state: Boolean): Result<Unit>       = safeCall { api.setShuffle(state).bodyOrThrow() }
    suspend fun setRepeat(state: String): Result<Unit>         = safeCall { api.setRepeat(state).bodyOrThrow() }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> retrofit2.Response<T>.bodyOrThrow(): T =
        when {
            code() == 204  -> Unit as T
            isSuccessful   -> body()!!
            code() == 429  -> {
                val retryAfter = headers()["Retry-After"]
                throw Exception("HTTP 429: Retry-After=${retryAfter ?: "unknown"}")
            }
            else           -> throw Exception("HTTP ${code()}: ${errorBody()?.string()}")
        }
}
