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
        // savedLegOk stays false on failure: leg 4's positive control keys on it. savedFollowCount
        // is what leg 1 REPORTED (items.size) — deliberately separate from savedCandidates, which is
        // only what parsed out to a show uri: "zero follows" and "follows exist but the payload
        // shape parsed to nothing" must reach different control branches (the second must NOT
        // authorise writes — it is exactly the unknown-shape case this spike exists to measure).
        var savedLegOk       = false
        var savedFollowCount = 0
        var savedId         : String? = null
        var savedCandidates : List<Pair<String, String>> = emptyList()   // uri to name
        getSavedShows().fold(
            onSuccess = { response ->
                val items = response.items.orEmpty()
                val first = items.firstOrNull()?.show
                line("1) GET me/shows?limit=5 -> 2xx OK, total=${response.total}, items=${items.size}, first=${first?.name ?: "-"}")
                savedLegOk       = true
                savedFollowCount = items.size
                savedId          = first?.id
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
        // ONLY leg-3 search hits are ever mutated, minus anything leg 1 reported as followed.
        // A leg-1 show can only reach the write legs in the state where me/library/contains
        // disagrees with me/shows — i.e. it reads `false` for a show the user genuinely follows —
        // and that is exactly the disagreement this spike exists to measure, so contains cannot
        // be the guard for itself: the PUT would be a no-op on an already-saved show and the
        // DELETE would unfollow a real subscription. Zero diagnostic value, real data loss.
        //
        // The `followed` filter is insurance only (me/shows is read at limit=5, so it is not a
        // complete follow-set — and the limit must stay 5 because the GO/NO-GO matrix keys on
        // leg 1's logged shape). The load-bearing guard is the 4-pre positive control below.
        //
        // Both the 4a pre-check and the 4d re-check go through containsShowRaw, not the
        // production isInLibrary: isInLibrary launders a 2xx that does not answer for this uri
        // into `false`, which is what would authorise the wrong DELETE (4a) and what would let
        // "no answer" read as "net state unchanged" (4d).
        val followed    = savedCandidates.map { it.first }.toSet()
        val candidates  = searchCandidates.filterNot { it.first in followed }
        val filteredOut = searchCandidates.size - candidates.size
        line("4) mutation candidates: leg-3 search hits ONLY (leg-1 followed shows are never mutated) -> ${candidates.size} candidate(s), $filteredOut filtered out as already followed (leg 1 ${if (savedLegOk) "ok" else "FAILED, so nothing could be filtered"}).")
        if (candidates.isEmpty()) {
            if (searchCandidates.isEmpty()) {
                line("4) me/library -> SKIPPED (leg 3 yielded no show uri, so there is no show safe to toggle)")
            } else {
                line("4) me/library -> SKIPPED (every one of leg 3's ${searchCandidates.size} hits is already followed — unfollow one in Spotify and run the spike again)")
            }
        } else {
            // ── (4-pre) Read-only positive control on contains ───────────────
            // Before any write, prove contains answers `true` for a show me/shows just said the
            // user follows. If it does not, contains cannot guard the write legs at all.
            val controlPassed: Boolean = when {
                !savedLegOk -> {
                    line("4-pre) control SKIPPED: leg 1 failed, so the follow-set is unknown and contains cannot be trusted — WRITE LEGS NOT EXERCISED.")
                    false
                }
                savedFollowCount == 0 -> {
                    line("4-pre) control not needed: me/shows returned 2xx with items=0 (no followed shows), so no candidate can be followed — a toggle cannot destroy a subscription.")
                    true
                }
                savedCandidates.isEmpty() -> {
                    // Leg 1 reported follows, yet nothing parsed to a show uri: the payload shape is
                    // not what Shows.kt models. No control is available, so no write is safe.
                    line("4-pre) control UNAVAILABLE: me/shows reported $savedFollowCount follow(s) but none parsed to a show uri (unexpected item shape — see the leg-1 line's first=) — WRITE LEGS NOT EXERCISED. Headline spike result: fix the show model before trusting any leg.")
                    false
                }
                else -> {
                    val (controlUri, controlName) = savedCandidates[0]
                    containsShowRaw(controlUri).fold(
                        onSuccess = { answer ->
                            if (answer == true) {
                                line("4-pre) control OK: contains=true for \"$controlName\", a show me/shows reports as followed — contains sees legacy show follows, so contains=false on a candidate is trustworthy.")
                                true
                            } else {
                                line("4-pre) contains disagrees with me/shows for a known-followed show (${answer ?: "2xx OK but EMPTY/NULL body"}) for \"$controlName\" — WRITE LEGS NOT EXERCISED, contains cannot guard them. Headline spike result: the unified library does NOT answer for legacy show follows.")
                                false
                            }
                        },
                        onFailure = {
                            line("4-pre) control read -> ${fail(it)} — WRITE LEGS NOT EXERCISED, contains cannot guard them.")
                            false
                        },
                    )
                }
            }
            // A failed control has already logged "WRITE LEGS NOT EXERCISED", so seed the loop as
            // already resolved: no candidate is read or touched, and no summary line is added.
            var resolved = !controlPassed
            for ((uri, name) in candidates) {
                if (resolved) break
                val check = containsShowRaw(uri)
                val error = check.exceptionOrNull()
                if (error != null) {
                    line("4a) GET me/library/contains?uris=$uri -> ${fail(error)}")
                    line("4) WRITE LEGS NOT EXERCISED: the contains read failed, so a save/remove round trip would prove nothing.")
                    resolved = true
                    break
                }
                // Errors are handled above, so null here is a 2xx that did not answer for this uri.
                val contained = check.getOrNull()
                line("4a) GET me/library/contains?uris=$uri -> 2xx OK, contains=${contained ?: "no answer (EMPTY/NULL body)"} (\"$name\")")
                if (contained == null) {
                    line("4b) contains returned 2xx but did not answer for this show uri — skipped mutation for \"$name\" (a PUT/DELETE pair on an unanswered check could unfollow a real subscription); trying the next candidate")
                    continue
                }
                if (contained) {
                    line("4b) contains=true, skipped mutation for \"$name\" — trying the next candidate")
                    continue
                }
                val preCheckWasConfirmedFalse = contained == false
                val put = saveToLibrary(uri)
                line("4b) PUT me/library?uris=$uri -> ${put.fold({ "2xx OK" }, { fail(it) })}  (\"$name\")")
                if (put.isFailure) {
                    line("4c) DELETE me/library -> SKIPPED (the PUT failed, nothing to undo)")
                } else {
                    if (!preCheckWasConfirmedFalse) {
                        // Defensive, and unreachable while the 4-pre control gates this loop and
                        // 4a `continue`s on anything but a confirmed false. If it ever fires, the
                        // PUT may have saved a show the user already followed, so the DELETE
                        // below is not a safe undo.
                        line("!! LIBRARY LEFT DIRTY (maybe): \"$name\" ($uri) was SAVED on a contains pre-check that could not be trusted — verify it by hand in Spotify.")
                    }
                    val delete = removeFromLibrary(uri)
                    line("4c) DELETE me/library?uris=$uri -> ${delete.fold({ "2xx OK" }, { fail(it) })}")
                    if (delete.isFailure) {
                        line("!! LIBRARY LEFT DIRTY: \"$name\" ($uri) was SAVED and the DELETE failed — unfollow it by hand in Spotify.")
                    }
                    containsShowRaw(uri).fold(
                        onSuccess = { after ->
                            line(
                                when {
                                    after == false && preCheckWasConfirmedFalse ->
                                        "4d) contains re-check -> false, and the pre-check was a confirmed false: net state unchanged."
                                    after == false ->
                                        "4d) contains re-check -> false, but the pre-check was not a confirmed false — net state NOT proven unchanged."
                                    after == true ->
                                        "4d) contains re-check -> true: \"$name\" is STILL in the library after the DELETE — verify it by hand in Spotify."
                                    else ->
                                        "4d) contains re-check -> 2xx OK but EMPTY/NULL body: the net state cannot be confirmed — verify \"$name\" by hand in Spotify."
                                }
                            )
                        },
                        onFailure = { line("4d) contains re-check -> ${fail(it)} — the net state cannot be confirmed; verify \"$name\" by hand in Spotify.") },
                    )
                }
                resolved = true
                break
            }
            if (!resolved) {
                line("4) WRITE LEGS NOT EXERCISED: none of the ${candidates.size} candidate shows could be toggled (each read back as already saved, or contains did not answer for it).")
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

    // TEMPORARY — podcast API spike, remove after go/no-go
    // The spike's own read of me/library/contains. Unlike the production isInLibrary (which two
    // saved-state UIs depend on, so its type must not widen), a 2xx whose body does not answer
    // for this uri — [] or [null] — stays distinguishable from a real `false`: laundering "no
    // answer" into false is what would authorise a PUT/DELETE pair on a show the user follows.
    private suspend fun containsShowRaw(uri: String): Result<Boolean?> =
        safeCall { api.checkSavedTracks(uri) }.map { it.firstOrNull() }

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
