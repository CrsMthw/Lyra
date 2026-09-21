package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Hard cap the Search endpoint puts on `limit` — also the step between search pages. */
const val SEARCH_PAGE_SIZE = 10

/**
 * A chunked playlist removal that got part of the way: [removed] is already gone server-side when
 * a later chunk failed. Carried on the [Result.failure] so the caller can commit that subset before
 * reporting the error, instead of telling the user nothing was removed while the playlist is
 * already shorter. Reuses `cause.message` so the "HTTP <code>: <body>" text the error dialog shows
 * is unchanged. See [SpotifyRepository.removeTracksFromPlaylist].
 */
class PartialRemovalException(
    val removed: List<String>,
    cause      : Throwable,
) : Exception(cause.message, cause)

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

    /**
     * Every playlist `me/playlists` knows about (2026-09-21): pages of 50, the offset advanced by
     * [UserPlaylistsResponse.rawCount] (never by the filtered `items.size`), stopping on
     * `next == null || rawCount == 0` — the shape `LibraryViewModel.loadCollections` pages saved
     * albums with. [UserPlaylistsSweep.total] is the endpoint's own count from the FIRST page, so
     * rows and count come from the same source. A failure on the first page is a failure; a failure
     * on a later page returns the prefix with `complete = false`, and the caller decides whether a
     * prefix is worth showing (the Library shows one only into an EMPTY list, never over a cached
     * full one, and never persists it). Callers gate on `isRateLimited()` as for any sweep.
     */
    suspend fun getAllUserPlaylists(): Result<UserPlaylistsSweep> =
        sweepUserPlaylists { offset -> getUserPlaylists(limit = 50, offset = offset) }

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

    suspend fun getRecentlyPlayed(limit: Int = 50): Result<RecentlyPlayedResponse> = safeCall {
        api.getRecentlyPlayed(limit)
    }

    suspend fun getTopTracks(timeRange: String = "short_term", limit: Int = 20): Result<Paged<SpotifyTrack>> = safeCall {
        api.getTopTracks(timeRange, limit)
    }

    suspend fun getTopArtists(timeRange: String = "short_term", limit: Int = 20): Result<Paged<SpotifyArtist>> = safeCall {
        api.getTopArtists(timeRange, limit)
    }

    suspend fun getSavedAlbums(limit: Int = 50, offset: Int = 0): Result<SavedAlbumsResponse> = safeCall {
        api.getSavedAlbums(limit, offset)
    }

    suspend fun getFollowedArtists(after: String? = null): Result<FollowedArtistsResponse> = safeCall {
        api.getFollowedArtists(after = after)
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

    // The API caps `limit` at 10, so paging by `offset` is the only way past the first page. The
    // endpoint takes a SINGLE offset for however many types it is asked for, which is why [type] is
    // a parameter: the Search screen's FIRST page asks for all three at once at offset 0 (one round
    // trip fills all three tabs, so switching tabs is instant), and every page after that asks for
    // exactly ONE type at that type's own offset — so the three tabs page independently instead of
    // sharing a cursor and dragging each other along. No `playlist` type: the API returns no track
    // contents for playlists you don't own, so finding them is pointless.
    suspend fun search(
        query : String,
        type  : String = "track,album,artist",
        offset: Int    = 0,
    ): Result<SearchResponse> = safeCall {
        api.search(query = query, type = type, limit = SEARCH_PAGE_SIZE, offset = offset)
    }

    suspend fun addToQueue(trackUri: String): Result<Unit> = safeCall {
        api.addToQueue(trackUri)
    }

    // ── Podcast shows ─────────────────────────────────────────────────────────
    // One method per endpoint, no hidden retries: the `market=from_token` fallback documented in
    // docs/SPOTIFY.md is a screen-level policy (see `ShowDetailViewModel.fetchEpisodePage`), not a
    // repository one, so a caller asking for one page still makes exactly one request and the
    // spike's leg-2 "(no market)" line keeps meaning what it says.
    //
    // Follow / unfollow / followed-status for a show are deliberately NOT here: they are the
    // unified saveToLibrary / removeFromLibrary / isInLibrary below with `spotify:show:<id>` —
    // the same path albums and artists take, since PUT/DELETE me/shows are deprecated.

    /** `GET me/shows` — the user's followed podcasts, offset-paged (`limit` caps at 50). */
    suspend fun getSavedShows(limit: Int = 50, offset: Int = 0): Result<SavedShowsResponse> = safeCall {
        api.getSavedShows(limit, offset)
    }

    /** `GET shows/{id}` — the full show; also embeds its first page of episodes. */
    suspend fun getShow(id: String, market: String? = null): Result<SpotifyShow> = safeCall {
        api.getShow(id, market)
    }

    /** `GET shows/{id}/episodes` — newest first, offset-paged (`limit` caps at 50). */
    suspend fun getShowEpisodes(
        id     : String,
        limit  : Int     = 50,
        offset : Int     = 0,
        market : String? = null,
    ): Result<ShowPage<SpotifyEpisode>> = safeCall {
        api.getShowEpisodes(id, limit, offset, market)
    }

    // ── Unified library save/remove/check — works for ANY Spotify uri (track, album, artist,
    //    show…); the Feb-2026 API folded all follows/saves into me/library. The api methods are
    //    named for tracks (their original use) but just pass the uris through.
    suspend fun saveToLibrary(uri: String): Result<Unit> = safeCall { api.saveTracks(uri) }

    suspend fun removeFromLibrary(uri: String): Result<Unit> = safeCall { api.removeTracks(uri) }

    suspend fun isInLibrary(uri: String): Result<Boolean> = safeCall {
        api.checkSavedTracks(uri).firstOrNull() ?: false
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
    }

    /** Deletes an owned playlist (Spotify models this as unfollowing it). */
    suspend fun deletePlaylist(playlistId: String): Result<Unit> = safeCall {
        api.unfollowPlaylist(playlistId)
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackUri: String): Result<Unit> = safeCall {
        api.removeItemsFromPlaylist(playlistId, RemoveItemsRequest(listOf(RemoveItemEntry(trackUri))))
    }

    /**
     * Batch form of [removeTrackFromPlaylist], backing the Library's multi-select removal: the whole
     * selection goes out in ONE `RemoveItemsRequest` instead of one request per track. Spotify caps
     * the request at 100 items, so a larger selection is sent as sequential chunks (sequential, not
     * parallel — each call moves the playlist's snapshot on). Duplicate uris are collapsed; as with
     * the single-track call no `positions` are sent, so a uri that appears twice in the playlist is
     * removed everywhere it appears.
     *
     * Each chunk gets its OWN [safeCall] rather than one around the loop, because a chunk that
     * succeeds is already committed server-side: a throw on chunk 2 (a dropped connection, a 429)
     * must not be reported as "nothing was removed" while chunk 1's 100 tracks are gone. The first
     * failure stops the loop and returns a [PartialRemovalException] carrying what went — unless
     * nothing went, in which case the bare cause is returned as before.
     */
    suspend fun removeTracksFromPlaylist(playlistId: String, uris: List<String>): Result<Unit> {
        val removed = mutableListOf<String>()
        for (chunk in uris.distinct().chunked(100)) {
            val cause = safeCall {
                api.removeItemsFromPlaylist(playlistId, RemoveItemsRequest(chunk.map { RemoveItemEntry(it) }))
            }.exceptionOrNull()
            if (cause != null) return Result.failure(
                if (removed.isEmpty()) cause else PartialRemovalException(removed, cause)
            )
            removed += chunk
        }
        return Result.success(Unit)
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
    suspend fun setVolume(volumePercent: Int): Result<Unit>    = safeCall { api.setVolume(volumePercent) }

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

/**
 * The paging loop behind [SpotifyRepository.getAllUserPlaylists], extracted so unit tests can
 * supply a fake page fetcher without constructing a full repository (which needs `EncryptedPrefs`
 * and therefore a `Context`).
 */
internal suspend fun sweepUserPlaylists(
    fetch: suspend (offset: Int) -> Result<UserPlaylistsResponse>,
): Result<UserPlaylistsSweep> {
    val items = mutableListOf<SpotifyPlaylist>()
    var offset = 0
    var total = 0
    var first = true
    while (true) {
        val page = fetch(offset).getOrElse { e ->
            return if (first) Result.failure(e)
            else Result.success(UserPlaylistsSweep(items, total, complete = false, error = e))
        }
        if (first) { total = page.total; first = false }
        items += page.items
        offset += page.rawCount
        if (page.next == null || page.rawCount == 0) break
    }
    return Result.success(UserPlaylistsSweep(items, total, complete = true))
}
