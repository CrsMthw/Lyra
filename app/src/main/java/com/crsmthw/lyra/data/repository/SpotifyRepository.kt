package com.crsmthw.lyra.data.repository

import android.util.Log
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.remote.SpotifyApiService
import com.crsmthw.lyra.data.remote.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Hard cap the Search endpoint puts on `limit` — also the step between search pages. */
const val SEARCH_PAGE_SIZE = 10

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

    // The API caps `limit` at 10, so paging by `offset` is the only way past the first page. One
    // offset covers all three types (the endpoint takes a single one) — a type that runs out just
    // stops contributing items to later pages. No `playlist` type: the API returns no track
    // contents for playlists you don't own, so finding them is pointless.
    suspend fun search(query: String, offset: Int = 0): Result<SearchResponse> = safeCall {
        api.search(query = query, type = "track,album,artist", limit = SEARCH_PAGE_SIZE, offset = offset)
    }

    suspend fun addToQueue(trackUri: String): Result<Unit> = safeCall {
        api.addToQueue(trackUri)
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
     */
    suspend fun removeTracksFromPlaylist(playlistId: String, uris: List<String>): Result<Unit> = safeCall {
        uris.distinct().chunked(100).forEach { chunk ->
            api.removeItemsFromPlaylist(playlistId, RemoveItemsRequest(chunk.map { RemoveItemEntry(it) }))
        }
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

    // ── TEMPORARY — podcast API spike, remove after go/no-go ─────────────────
    //
    // A one-shot probe of the whole podcast surface with the app's real dev-mode token, because
    // "documented" ≠ "returns content" (featured-playlists taught us that) and the token lives in
    // EncryptedPrefs on the device, so these calls cannot be made from a desktop.
    //
    // Reads the legs in spec order but EXECUTES in dependency order (1 → 3 → 2 → 5 → 4): leg 2
    // needs a show id and its fallback source is the search in leg 3.
    //
    // Every leg goes through safeCall, so a failure line already carries "HTTP <code>: <body>" —
    // which is the load-bearing detail: a plain 403 "Forbidden" means the endpoint is gone for our
    // app tier (NO-GO), while 403 "Insufficient client scope" is a one-line SCOPES fix (GO).
    // Never touch errorBody() here — safeCall has already consumed that stream.
    suspend fun runPodcastSpike(): List<String> {
        val log = mutableListOf<String>()
        fun line(text: String) {
            log += text
            Log.i(SPIKE_TAG, text)
        }

        fun fail(t: Throwable): String = "FAIL ${t::class.java.simpleName}: ${t.message}"

        line("Podcast API spike — token scopes as shipped (no user-read-playback-position).")

        // ── (1) Followed shows ───────────────────────────────────────────────
        var savedId         : String? = null
        var savedCandidates : List<Pair<String, String>> = emptyList()   // uri to name
        getSavedShows().fold(
            onSuccess = { response ->
                val items = response.items.orEmpty()
                val first = items.firstOrNull()?.show
                line("1) GET me/shows?limit=5 -> 2xx OK, total=${response.total}, items=${items.size}, first=${first?.name ?: "-"}")
                savedId         = first?.id
                savedCandidates = items.mapNotNull { it.show }.mapNotNull { show ->
                    show.uri?.let { uri -> uri to (show.name ?: uri) }
                }
            },
            onFailure = { line("1) GET me/shows?limit=5 -> ${fail(it)}") },
        )

        // ── (3) Search for shows ─────────────────────────────────────────────
        var searchId         : String? = null
        var searchCandidates : List<Pair<String, String>> = emptyList()   // uri to name
        searchShows(SPIKE_QUERY).fold(
            onSuccess = { response ->
                val items = response.shows?.items.orEmpty()
                val first = items.firstOrNull()
                line("3) GET search?q=\"$SPIKE_QUERY\"&type=show&limit=3 -> 2xx OK, total=${response.shows?.total}, items=${items.size}, first=${first?.name ?: "-"}")
                searchId         = first?.id
                searchCandidates = items.mapNotNull { show ->
                    show.uri?.let { uri -> uri to (show.name ?: uri) }
                }
            },
            onFailure = { line("3) GET search?type=show -> ${fail(it)}") },
        )

        // ── (2) Episodes of a show ───────────────────────────────────────────
        val probeId = savedId ?: searchId
        if (probeId == null) {
            line("2) GET shows/{id}/episodes -> SKIPPED (legs 1 and 3 yielded no show id)")
        } else {
            getShowEpisodes(probeId).fold(
                onSuccess = { page ->
                    val items = page.items.orEmpty()
                    line("2) GET shows/$probeId/episodes?limit=5 (no market) -> 2xx OK, total=${page.total}, items=${items.size}, first=${items.firstOrNull()?.name ?: "-"}")
                    // A 200 with zero items is ambiguous: a hollow endpoint, or the documented
                    // "no market and no user country -> content considered unavailable". Retry with
                    // the market value the app already uses on getArtistAlbums to tell them apart.
                    if (items.isEmpty()) {
                        getShowEpisodes(probeId, market = SPIKE_MARKET).fold(
                            onSuccess = { retry ->
                                val retryItems = retry.items.orEmpty()
                                line("2b) same call with market=$SPIKE_MARKET -> 2xx OK, total=${retry.total}, items=${retryItems.size}, first=${retryItems.firstOrNull()?.name ?: "-"}")
                            },
                            onFailure = { line("2b) same call with market=$SPIKE_MARKET -> ${fail(it)}") },
                        )
                    }
                },
                onFailure = { line("2) GET shows/$probeId/episodes -> ${fail(it)}") },
            )
        }

        // ── (5) Single show (a future detail screen needs this) ──────────────
        if (probeId == null) {
            line("5) GET shows/{id} -> SKIPPED (no show id)")
        } else {
            getShow(probeId).fold(
                onSuccess = { show ->
                    line("5) GET shows/$probeId -> 2xx OK, name=${show.name ?: "-"}, total_episodes=${show.totalEpisodes}, embedded episodes=${show.episodes?.items.orEmpty().size}, publisher=${show.publisher ?: "(absent)"}")
                },
                onFailure = { line("5) GET shows/$probeId -> ${fail(it)}") },
            )
        }

        // ── (4) Library membership, then a net-zero save/remove round trip ───
        // Candidates in preference order: every search hit first (any of them is fair game to
        // toggle), then the already-followed shows as a last resort. Do NOT stop at the first
        // already-saved candidate — the write legs are the ones that decide whether a follow
        // button is buildable, and "the top hit happens to be followed" must not silently
        // swallow them.
        val candidates = (searchCandidates + savedCandidates).distinctBy { it.first }
        if (candidates.isEmpty()) {
            line("4) me/library -> SKIPPED (legs 1 and 3 yielded no show uri)")
        } else {
            var resolved = false
            for ((uri, name) in candidates) {
                val check = isInLibrary(uri)
                val error = check.exceptionOrNull()
                if (error != null) {
                    line("4a) GET me/library/contains?uris=$uri -> ${fail(error)}")
                    line("4) WRITE LEGS NOT EXERCISED: the contains read failed, so a save/remove round trip would prove nothing.")
                    resolved = true
                    break
                }
                val contained = check.getOrDefault(false)
                line("4a) GET me/library/contains?uris=$uri -> 2xx OK, contains=$contained (\"$name\")")
                if (contained) {
                    line("4b) contains=true, skipped mutation for \"$name\" — trying the next candidate")
                    continue
                }
                val put = saveToLibrary(uri)
                line("4b) PUT me/library?uris=$uri -> ${put.fold({ "2xx OK" }, { fail(it) })}  (\"$name\")")
                if (put.isFailure) {
                    line("4c) DELETE me/library -> SKIPPED (the PUT failed, nothing to undo)")
                } else {
                    val delete = removeFromLibrary(uri)
                    line("4c) DELETE me/library?uris=$uri -> ${delete.fold({ "2xx OK" }, { fail(it) })}")
                    if (delete.isFailure) {
                        line("!! LIBRARY LEFT DIRTY: \"$name\" ($uri) was SAVED and the DELETE failed — unfollow it by hand in Spotify.")
                    }
                    isInLibrary(uri).fold(
                        onSuccess = { line("4d) contains re-check -> $it (expected false = net state unchanged)") },
                        onFailure = { line("4d) contains re-check -> ${fail(it)}") },
                    )
                }
                resolved = true
                break
            }
            if (!resolved) {
                line("4) WRITE LEGS NOT EXERCISED: all ${candidates.size} candidate shows are already in the library. Unfollow one in Spotify and run the spike again — the PUT/DELETE result is what decides whether a follow button is buildable.")
            }
        }

        line("Spike done. A 429 on any leg means inconclusive — wait a minute and run it again.")
        return log
    }

    private suspend fun getSavedShows(): Result<SavedShowsResponse> = safeCall {
        api.getSavedShows()
    }

    private suspend fun getShow(id: String): Result<SpotifyShow> = safeCall {
        api.getShow(id)
    }

    private suspend fun getShowEpisodes(id: String, market: String? = null): Result<ShowPage<SpotifyEpisode>> = safeCall {
        api.getShowEpisodes(id, market = market)
    }

    private suspend fun searchShows(query: String): Result<ShowSearchResponse> = safeCall {
        api.searchShows(query = query)
    }

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

// TEMPORARY — podcast API spike, remove after go/no-go
private const val SPIKE_TAG    = "PodcastSpike"
private const val SPIKE_QUERY  = "the daily"
private const val SPIKE_MARKET = "from_token"   // same value the app already sends on getArtistAlbums
