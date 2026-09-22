package com.crsmthw.lyra.ui.ilyra

import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.CachedTrackList
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LibraryCacheData
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.model.AlbumTrack
import com.crsmthw.lyra.data.remote.model.ShowPage
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyEpisode
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyShow
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.remote.model.SpotifyUser
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.ui.ilyra.nav.LcdLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Browse-data facade for the iLyra. Cache-first, one read per screen entry, every network call
 * behind `isRateLimited()` / `noteRateLimited()`. Offsets advance by `rawCount`, list elements
 * are null-safe.
 *
 * This is a plain class, not a ViewModel — it is scoped to `ILyraViewModel`'s lifetime and shares
 * the same coroutine scope through the caller.
 */
class ILyraLibrary(
    private val libraryCache: LibraryCache,
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) {
    /** Snapshot of the cache at the last load. */
    private var cacheSnapshot: LibraryCacheData? = null

    /** Rate-limit gate: fails with a descriptive message when the global gate is tripped. */
    private fun checkRateLimit(): Result<Unit> =
        if (playerStateManager.isRateLimited()) Result.failure(RateLimitedException())
        else Result.success(Unit)

    /** Marks the global rate-limit gate when a 429 is detected in a failure message. */
    private fun noteIfRateLimited(error: Throwable?) {
        if (error?.message?.contains("429") == true) playerStateManager.noteRateLimited()
    }

    private suspend fun loadCache(): LibraryCacheData? = withContext(Dispatchers.IO) {
        libraryCache.load().also { cacheSnapshot = it }
    }

    /** Re-reads the cache after a persist (e.g. after saving collections). */
    private suspend fun refreshCache(): LibraryCacheData? = loadCache()

    // ── User ─────────────────────────────────────────────────────────────────

    /**
     * Resolve the signed-in user's id. Cache-first, then one rate-limit-gated network call. The
     * result is cached for the session by the caller.
     */
    suspend fun resolveUserId(): String? {
        val cached = withContext(Dispatchers.IO) {
            libraryCache.load()?.user?.id?.takeIf { it.isNotBlank() }
        }
        if (cached != null) return cached
        if (playerStateManager.isRateLimited()) return null
        return repository.getCurrentUser()
            .onFailure { noteIfRateLimited(it) }
            .getOrNull()?.id?.takeIf { it.isNotBlank() }
    }

    // ── Saved albums ─────────────────────────────────────────────────────────

    data class AlbumListResult(
        val albums: List<SpotifyAlbum>,
    )

    /**
     * Returns the user's saved albums. Cache-first; if absent, pages `getSavedAlbums` to the end
     * and persists via `saveCollections`.
     */
    suspend fun savedAlbums(): Result<AlbumListResult> {
        val cache = loadCache()
        val cached = cache?.savedAlbums
        if (!cached.isNullOrEmpty()) return Result.success(AlbumListResult(cached))

        checkRateLimit().onFailure { return Result.failure(it) }

        val albums = mutableListOf<SpotifyAlbum>()
        var offset = 0
        var complete = false
        var sweepError: Throwable? = null
        while (true) {
            val page = repository.getSavedAlbums(limit = 50, offset = offset)
                .onFailure { noteIfRateLimited(it); sweepError = it }
                .getOrNull() ?: break
            val items = page.items.orEmpty()
            albums += items.mapNotNull { it.album }
            offset += page.rawCount
            if (page.next == null || page.rawCount == 0) { complete = true; break }
        }

        // Only a COMPLETE sweep is persisted — a truncated prefix must never replace a whole
        // cached list (mirrors LibraryViewModel.loadCollections).
        if (complete && albums.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                libraryCache.saveCollections(savedAlbums = albums)
            }
            refreshCache()
        }
        // Nothing at all came back and the sweep did not finish → a FAILURE the LCD can show
        // ("Couldn't load"), never an empty list masquerading as "No Albums".
        if (!complete && albums.isEmpty()) return Result.failure(sweepError ?: IllegalStateException("no page"))
        return Result.success(AlbumListResult(albums))
    }

    // ── Album tracks ─────────────────────────────────────────────────────────

    data class AlbumTracksResult(
        val tracks: List<AlbumTrack>,
        val albumName: String,
        val albumUri: String,
    )

    /**
     * Fetches all tracks for a single album via `getAlbum`. The API caps at ~50 tracks per album,
     * which the rest of the app also accepts (no paging).
     */
    suspend fun albumTracks(albumId: String): Result<AlbumTracksResult> {
        checkRateLimit().onFailure { return Result.failure(it) }

        return repository.getAlbum(albumId)
            .onFailure { noteIfRateLimited(it) }
            .map { album ->
                AlbumTracksResult(
                    tracks = album.tracks?.items ?: emptyList(),
                    albumName = album.name,
                    albumUri = "spotify:album:${album.id}",
                )
            }
    }

    // ── Followed artists ─────────────────────────────────────────────────────

    data class ArtistListResult(
        val artists: List<SpotifyArtist>,
    )

    /**
     * Returns followed artists. Cache-first; if absent, cursor-pages `getFollowedArtists` to the
     * end and persists.
     */
    suspend fun followedArtists(): Result<ArtistListResult> {
        val cache = loadCache()
        val cached = cache?.followedArtists
        if (!cached.isNullOrEmpty()) return Result.success(ArtistListResult(cached))

        checkRateLimit().onFailure { return Result.failure(it) }

        val artists = mutableListOf<SpotifyArtist>()
        var after: String? = null
        var complete = false
        var sweepError: Throwable? = null
        while (true) {
            val page = repository.getFollowedArtists(after)
                .onFailure { noteIfRateLimited(it); sweepError = it }
                .getOrNull()?.artists ?: break
            val items = page.items.orEmpty()
            artists += items
            after = page.cursors?.after
            if (after == null || page.rawCount == 0) { complete = true; break }
        }

        if (complete && artists.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                libraryCache.saveCollections(followedArtists = artists)
            }
            refreshCache()
        }
        if (!complete && artists.isEmpty()) return Result.failure(sweepError ?: IllegalStateException("no page"))
        return Result.success(ArtistListResult(artists))
    }

    // ── Artist albums ────────────────────────────────────────────────────────

    data class ArtistAlbumsResult(
        val albums: List<SpotifyAlbum>,
        val hasMore: Boolean,
        val nextOffset: Int,
    )

    /**
     * One page of an artist's albums (10 per page, the API's cap for this endpoint).
     */
    suspend fun artistAlbums(artistId: String, offset: Int = 0): Result<ArtistAlbumsResult> {
        checkRateLimit().onFailure { return Result.failure(it) }

        return repository.getArtistAlbums(artistId, offset)
            .onFailure { noteIfRateLimited(it) }
            .map { paged ->
                ArtistAlbumsResult(
                    albums = paged.items,
                    hasMore = paged.next != null,
                    nextOffset = offset + paged.rawCount,
                )
            }
    }

    // ── Owned playlists ──────────────────────────────────────────────────────

    data class PlaylistListResult(
        val playlists: List<SpotifyPlaylist>,
        /** True when this result came from the library cache — the caller then sweeps. */
        val fromCache: Boolean = false,
        /** False when a mid-sweep failure truncated the list. Cache results are always complete. */
        val complete: Boolean = true,
    )

    /**
     * Returns playlists the user owns. Cache-first (filtered by owner), then the PAGED sweep
     * `getAllUserPlaylists()` so users with more than 50 playlists see every owned one.
     * The sweep is NOT persisted — the cache holds ALL playlists (owned + followed) for the Library,
     * and overwriting it with owned-only would silently drop followed playlists from the Library screen.
     *
     * When the cache holds a non-empty owned prefix it is returned immediately with [fromCache] = true;
     * the caller shows it at once and then calls [sweepOwnedPlaylists] to replace it with the full
     * set (cache-then-reconcile, matching the `playlistTracks` / `reconcilePlaylist` pattern).
     */
    suspend fun ownedPlaylists(userId: String): Result<PlaylistListResult> {
        val cache = loadCache()
        val cached = cache?.playlists
        if (!cached.isNullOrEmpty()) {
            val owned = cached.filter { it.owner?.id == userId }
            if (owned.isNotEmpty()) return Result.success(PlaylistListResult(owned, fromCache = true))
        }

        checkRateLimit().onFailure { return Result.failure(it) }

        return repository.getAllUserPlaylists()
            .onFailure { noteIfRateLimited(it) }
            .onSuccess { noteIfRateLimited(it.error) }
            .map { sweep ->
                PlaylistListResult(
                    playlists = sweep.items.filter { it.owner?.id == userId },
                    complete = sweep.complete,
                )
            }
    }

    /**
     * Runs the paged `getAllUserPlaylists()` sweep and returns the owned subset, or null when the
     * network is unavailable or rate-limited. Called after [ownedPlaylists] returned [fromCache] = true.
     * A mid-sweep failure returns the prefix with [PlaylistListResult.complete] = false — the caller
     * decides whether to replace the already-showing cached list.
     */
    suspend fun sweepOwnedPlaylists(userId: String): PlaylistListResult? {
        if (checkRateLimit().isFailure) return null
        val sweep = repository.getAllUserPlaylists()
            .onFailure { noteIfRateLimited(it) }
            .getOrNull() ?: return null
        noteIfRateLimited(sweep.error)
        val owned = sweep.items.filter { it.owner?.id == userId }
        return PlaylistListResult(owned, complete = sweep.complete)
    }

    // ── Playlist tracks ──────────────────────────────────────────────────────

    data class PlaylistTracksResult(
        val tracks: List<PlaylistTrackItem>,
        val hasMore: Boolean,
        val nextOffset: Int,
        /** True when this page came from the library cache (offset 0) — the caller then reconciles. */
        val fromCache: Boolean = false,
    )

    data class PlaylistTrackItem(
        val uri: String,
        val name: String,
        val allArtists: String,
    )

    /**
     * One page of a playlist's tracks. For offset 0, prefers the library cache (which already
     * has the track list from the Library screen's own load).
     *
     * Note: if `rawOffset` is null in the cached track list, we treat it as no-more-pages —
     * never fall back to `tracks.size` as the offset per the project's data rule.
     */
    suspend fun playlistTracks(
        playlistId: String,
        offset: Int = 0,
    ): Result<PlaylistTracksResult> {
        // For offset 0, try the library cache first.
        if (offset == 0) {
            val cached = withContext(Dispatchers.IO) {
                libraryCache.loadTrackList(playlistId)
            }
            if (cached != null && cached.tracks.isNotEmpty()) {
                val items = cached.tracks
                    .filter { it.isPlayable != false }
                    .map { PlaylistTrackItem(uri = it.uri, name = it.name, allArtists = it.allArtists) }
                // rawOffset is the API offset the next page starts at. Null means the cache was
                // written without offset info (liked songs, old cache) → no more pages. A non-null
                // rawOffset means there MAY be more — we let the next network page decide; the
                // worst case is one extra request that returns empty and clears hasMore.
                val nextOffset = cached.rawOffset
                return Result.success(
                    PlaylistTracksResult(
                        tracks = items,
                        hasMore = nextOffset != null,
                        nextOffset = nextOffset ?: 0,
                        fromCache = true,
                    ),
                )
            }
        }

        checkRateLimit().onFailure { return Result.failure(it) }

        return repository.getPlaylistTracks(playlistId, limit = 50, offset = offset)
            .onFailure { noteIfRateLimited(it) }
            .map { response ->
                val items = response.items
                    ?.mapNotNull { it.resolvedTrack }
                    ?.filter { it.isPlayable != false }
                    ?.map { PlaylistTrackItem(uri = it.uri, name = it.name, allArtists = it.allArtists) }
                    ?: emptyList()
                PlaylistTracksResult(
                    tracks = items,
                    hasMore = response.next != null && items.isNotEmpty(),
                    nextOffset = offset + response.rawCount,
                )
            }
    }

    // ── Reconcile (what the Library does after showing a cached list) ────────

    /**
     * Liked Songs: one `limit = 1` call for the server's total. New songs (a difference of 1..200)
     * are fetched newest-first and PREPENDED; a shrink or a bigger jump replaces the list with a
     * fresh first page, as `LibraryViewModel.selectLikedSongs` does. Writes the result back to the
     * shared cache under the new total. Returns the new full list, or null when nothing changed
     * (or the network was unavailable — the cache stays on screen).
     */
    suspend fun reconcileLikedSongs(cached: CachedTrackList?): List<SpotifyTrack>? {
        if (checkRateLimit().isFailure) return null
        val cachedTracks = cached?.tracks?.distinctBy { it.id } ?: emptyList()
        val cachedCount = cached?.snapshotId?.toIntOrNull() ?: cachedTracks.size
        val total = repository.getLikedSongs(limit = 1)
            .onFailure { noteIfRateLimited(it) }
            .getOrNull()?.total ?: return null
        val diff = total - cachedCount
        if (diff == 0 && cachedTracks.isNotEmpty()) return null

        if (diff in 1..200) {
            // Newest first, in pages of 50, until `diff` slots have been read.
            val fresh = mutableListOf<SpotifyTrack>()
            var offset = 0
            while (offset < diff) {
                val page = repository.getLikedSongs(limit = minOf(50, diff - offset), offset = offset)
                    .onFailure { noteIfRateLimited(it) }
                    .getOrNull() ?: return null
                fresh += (page.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                if (page.rawCount == 0) break
                offset += page.rawCount
            }
            // Atomic prepend: a page the indexer appends between our earlier cache read and this
            // write is kept rather than reverted (the old load() … saveTrackList(cached + page)
            // pair had exactly that window). prependToLikedSongs returns the merged list as written.
            val merged = withContext(Dispatchers.IO) {
                libraryCache.prependToLikedSongs(fresh, total)
            }
            return merged
        } else {
            // Shrunk, or jumped by more than we chase: start over with a fresh first page (the
            // background fetcher and the Library's paging refill the rest). A deliberate wholesale
            // reset the indexer re-seeds from, so saveTrackList is correct here.
            val page = repository.getLikedSongs(limit = 50, offset = 0)
                .onFailure { noteIfRateLimited(it) }
                .getOrNull() ?: return null
            val merged = (page.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
            withContext(Dispatchers.IO) {
                libraryCache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, total.toString(), merged)
            }
            return merged
        }
    }

    /**
     * A playlist shown from the cache: fetch a fresh first page (one call — `getPlaylistTracks` at
     * offset 0 is the playlist object with its first page embedded) and compare it with the cached
     * first page. Same uris in the same order → null (unchanged). Otherwise the fresh page replaces
     * the cached list (`rawOffset` = its raw size, so paging continues from there) and is returned.
     */
    suspend fun reconcilePlaylist(playlistId: String, cachedUris: List<String>): PlaylistTracksResult? {
        if (checkRateLimit().isFailure) return null
        val response = repository.getPlaylistTracks(playlistId, limit = 50, offset = 0)
            .onFailure { noteIfRateLimited(it) }
            .getOrNull() ?: return null
        val tracks = response.items
            ?.mapNotNull { it.resolvedTrack }
            ?.filter { it.isPlayable != false }
            ?: emptyList()
        val freshUris = tracks.map { it.uri }
        val sameStart = freshUris == cachedUris.take(freshUris.size)
        val sameLength = response.next != null || cachedUris.size == freshUris.size
        if (sameStart && sameLength) return null

        val existing = withContext(Dispatchers.IO) { libraryCache.loadTrackList(playlistId) }
        withContext(Dispatchers.IO) {
            // The snapshot id stays whatever the cache held: the Library compares it against the
            // playlist list's snapshot and simply refetches on a mismatch — one harmless extra call.
            libraryCache.saveTrackList(playlistId, existing?.snapshotId ?: "", tracks, response.rawCount)
        }
        return PlaylistTracksResult(
            tracks = tracks.map { PlaylistTrackItem(uri = it.uri, name = it.name, allArtists = it.allArtists) },
            hasMore = response.next != null && tracks.isNotEmpty(),
            nextOffset = response.rawCount,
        )
    }

    // ── Followed shows ───────────────────────────────────────────────────────

    data class ShowListResult(
        val shows: List<SpotifyShow>,
    )

    /**
     * Returns followed shows. Cache-first; if absent, pages `getSavedShows` to the end and
     * persists (without episodes — mirrors `LibraryViewModel.loadCollections`).
     */
    suspend fun followedShows(): Result<ShowListResult> {
        val cache = loadCache()
        val cached = cache?.followedShows
        if (!cached.isNullOrEmpty()) return Result.success(ShowListResult(cached))

        checkRateLimit().onFailure { return Result.failure(it) }

        val shows = mutableListOf<SpotifyShow>()
        var offset = 0
        var complete = false
        var sweepError: Throwable? = null
        while (true) {
            val page = repository.getSavedShows(limit = 50, offset = offset)
                .onFailure { noteIfRateLimited(it); sweepError = it }
                .getOrNull() ?: break
            val items = page.items.orEmpty()
            shows += items.mapNotNull { it.show?.copy(episodes = null) }
            offset += page.rawCount
            if (page.next == null || page.rawCount == 0) { complete = true; break }
        }

        if (complete && shows.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                libraryCache.saveCollections(followedShows = shows)
            }
            refreshCache()
        }
        if (!complete && shows.isEmpty()) return Result.failure(sweepError ?: IllegalStateException("no page"))
        return Result.success(ShowListResult(shows))
    }

    // ── Show episodes ────────────────────────────────────────────────────────

    data class EpisodeListResult(
        val episodes: List<EpisodeItem>,
        val hasMore: Boolean,
        val nextOffset: Int,
    )

    data class EpisodeItem(
        val id: String,
        val uri: String,
        val name: String,
        val subtitle: LcdLabel,
        val resumePositionMs: Long?,
        val fullyPlayed: Boolean,
    )

    private companion object {
        const val EPISODE_PAGE_SIZE = 50
        const val EPISODE_MARKET_FALLBACK = "from_token"
        /** Cap on the uris list sent to the player — mirrors ShowDetailScreen's constant. */
        const val EPISODE_QUEUE_LIMIT = 750
    }

    /**
     * Fetches one page of a show's episodes, newest first. Offset 0 uses `getShow` (which
     * embeds the first page), subsequent pages use `getShowEpisodes`. Applies the one-shot
     * `market=from_token` retry when items are empty (mirrors `ShowDetailViewModel`).
     */
    suspend fun showEpisodes(showId: String, offset: Int = 0): Result<EpisodeListResult> {
        checkRateLimit().onFailure { return Result.failure(it) }

        if (offset == 0) {
            // getShow embeds the first page of episodes.
            val showResult = repository.getShow(showId)
                .onFailure { noteIfRateLimited(it) }
            val show = showResult.getOrNull()
            if (show != null) {
                val page = show.episodes
                val episodes = page?.items
                if (episodes.isNullOrEmpty()) {
                    // One-shot market=from_token retry.
                    val retried = repository.getShow(showId, market = EPISODE_MARKET_FALLBACK)
                        .getOrNull()
                    val retriedPage = retried?.episodes
                    val retriedEpisodes = retriedPage?.items
                    return if (!retriedEpisodes.isNullOrEmpty()) {
                        Result.success(mapEpisodePage(retriedEpisodes, retriedPage, 0))
                    } else {
                        Result.success(mapEpisodePage(episodes ?: emptyList(), page, 0))
                    }
                }
                return Result.success(mapEpisodePage(episodes, page, 0))
            }
            return showResult.map {
                mapEpisodePage(it.episodes?.items ?: emptyList(), it.episodes, 0)
            }
        }

        // Subsequent pages.
        val result = repository.getShowEpisodes(showId, limit = EPISODE_PAGE_SIZE, offset = offset)
            .onFailure { noteIfRateLimited(it) }
        val page = result.getOrNull()
        if (page != null && page.items.isNullOrEmpty() && offset < (page.total ?: 0)) {
            // One-shot market=from_token retry.
            val retried = repository.getShowEpisodes(
                showId, limit = EPISODE_PAGE_SIZE, offset = offset, market = EPISODE_MARKET_FALLBACK,
            ).getOrNull()
            if (retried != null && !retried.items.isNullOrEmpty()) {
                return Result.success(mapEpisodeShowPage(retried, offset))
            }
        }
        return result.map { mapEpisodeShowPage(it, offset) }
    }

    private fun mapEpisodePage(
        episodes: List<SpotifyEpisode>,
        page: ShowPage<SpotifyEpisode>?,
        offset: Int,
    ): EpisodeListResult {
        val items = episodes.mapNotNull { ep -> mapEpisode(ep) }
        val rawCount = page?.rawCount ?: episodes.size
        val total = page?.total
        return EpisodeListResult(
            episodes = items,
            hasMore = total != null && offset + rawCount < total,
            nextOffset = offset + rawCount,
        )
    }

    private fun mapEpisodeShowPage(
        page: ShowPage<SpotifyEpisode>,
        offset: Int,
    ): EpisodeListResult {
        val items = page.items?.mapNotNull { ep -> mapEpisode(ep) } ?: emptyList()
        val total = page.total
        return EpisodeListResult(
            episodes = items,
            hasMore = total != null && offset + page.rawCount < total,
            nextOffset = offset + page.rawCount,
        )
    }

    private fun mapEpisode(ep: SpotifyEpisode): EpisodeItem? {
        val id = ep.id ?: return null
        val uri = ep.uri?.takeIf { it.isNotBlank() } ?: return null
        val name = ep.name ?: return null

        val fullyPlayed = ep.resumePoint?.fullyPlayed == true
        val resumeMs = ep.resumePoint?.resumePositionMs?.takeIf { it > 0L }
        // Reject a resume position in the last 10 s of a known duration (matches ShowDetailScreen).
        val effectiveResume = if (resumeMs != null && ep.durationMs != null &&
            resumeMs > ep.durationMs - 10_000L
        ) null else resumeMs

        // Subtitle: release date + duration or resume state (mirrors ShowDetailScreen's EpisodeRow),
        // as LABELS so the words come from strings.xml when the LCD resolves them.
        val playState: LcdLabel? = when {
            fullyPlayed -> LcdLabel.Res(R.string.show_episode_played)
            effectiveResume != null && ep.durationMs != null ->
                LcdLabel.ResArgs(R.string.show_episode_time_left, listOf(formatDuration(ep.durationMs - effectiveResume)))
            ep.durationMs != null -> LcdLabel.Text(formatDuration(ep.durationMs))
            else -> null
        }
        val subtitle = LcdLabel.Joined(
            listOfNotNull(ep.releaseDate?.take(10)?.let { LcdLabel.Text(it) }, playState),
        )

        return EpisodeItem(
            id = id,
            uri = uri,
            name = name,
            subtitle = subtitle,
            resumePositionMs = if (fullyPlayed) null else effectiveResume,
            fullyPlayed = fullyPlayed,
        )
    }

    /**
     * Build the episode queue from a given episode onward. Mirrors ShowDetailScreen's `queueFrom`:
     * the tapped episode followed by every episode after it in the loaded list (oldest direction).
     * Capped at [EPISODE_QUEUE_LIMIT]. Returns null when there is nothing to continue with.
     */
    fun buildEpisodeQueue(episodes: List<EpisodeItem>, fromId: String): List<String>? {
        val start = episodes.indexOfFirst { it.id == fromId }
        val tail = if (start >= 0) episodes.drop(start) else return null
        return tail.map { it.uri }
            .take(EPISODE_QUEUE_LIMIT)
            .takeIf { it.size > 1 }
    }
}

/** Marker for rate-limited failures so the VM can detect them. */
class RateLimitedException : Exception("Rate limited — try again later")

/** Format milliseconds as "Xh Ym" or "Xm" or "Xs". */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes} min"
        else -> "${totalSeconds}s"
    }
}
