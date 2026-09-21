package com.crsmthw.lyra.data.local

import android.util.Log
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the indexer knows about the Liked Songs cache. All fields are read from the cache, never from a rendered list. */
data class LikedSongsIndexState(
    /** Rows (filtered tracks) the cache holds; null before the first read. */
    val cached: Int? = null,
    /** The server's count, per the cache's `snapshotId`; null when unknown (no liked list cached yet). */
    val total: Int? = null,
    /** True once the raw offset has reached the total — nothing left to page (re-arms if the total grows). */
    val complete: Boolean = false,
    /** True while at least one demand keeps the paging loop alive. */
    val running: Boolean = false,
)

/**
 * THE liked-songs indexer — one app-scoped instance in `AppContainer`, shared by the foreground
 * service and the iPod (Checkpoint C, 2026-09-21). It owns the paging loop that used to live in
 * `LyraForegroundService.startLikedSongsFetcher()`: one `me/tracks` page per tick, the RAW offset
 * advanced by `rawCount` (never by a filtered size), re-seeded from the cache when the cache shrinks
 * under it, `isRateLimited()`-gated before every call and `noteRateLimited()` on a 429, each page
 * appended atomically through [LibraryCache.appendToLikedSongs].
 *
 * Two DEMANDS drive it. The service holds the background demand while it runs (one page every
 * [BACKGROUND_INTERVAL_MS], as before). The iPod holds the fast demand for its session (one page
 * every [FAST_INTERVAL_MS]) so Cover Flow's list fills in minutes rather than hours. The loop runs
 * while either demand is held; the interval is the fast one whenever the fast demand is held. Once
 * complete it idles at the background interval, re-checking the cache for a grown total.
 *
 * **Prepend interplay (inherited from the old loop).** A like or the iPod's reconcile PREPENDS rows,
 * so the cache grows and `rawOffset` lags by that many; the next page re-reads those rows from the
 * API, the atomic [LibraryCache.appendToLikedSongs] drops them as already held (de-duped by id),
 * and `rawOffset += rawCount` lands exactly on the true position — a one-page self-heal, not a bug.
 *
 * **Thread safety.** Demands may be flipped from the main thread (the iPod's composable lifecycle,
 * the foreground service's `onCreate`/`onDestroy`) while the loop runs on [Dispatchers.Default].
 * The two demand flags live in a [MutableStateFlow] so flips are atomic and the loop can wait on a
 * change via [MutableStateFlow.first].
 *
 * CONTRACT (frozen for the round): the public surface below. The data lane fills in the body.
 */
class LikedSongsIndexer(
    private val libraryCache: LibraryCache,
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) {
    private val _state = MutableStateFlow(LikedSongsIndexState())
    val state: StateFlow<LikedSongsIndexState> = _state.asStateFlow()

    private val _appended = MutableSharedFlow<List<SpotifyTrack>>(extraBufferCapacity = 16)
    /**
     * The filtered tracks each page GENUINELY added to the cache (already-held ids dropped), in
     * cache order, so a consumer showing the liked list can grow it without re-parsing the cache file.
     */
    val appended: SharedFlow<List<SpotifyTrack>> = _appended.asSharedFlow()

    // ── Demand tracking ──────────────────────────────────────────────────────
    // Two independent boolean demands packed into one StateFlow so a demand change wakes the loop's
    // wait early (the loop watches this flow with withTimeoutOrNull + first { it != snapshot }).

    private data class Demands(val background: Boolean = false, val fast: Boolean = false) {
        val any: Boolean get() = background || fast
    }

    private val demands = MutableStateFlow(Demands())

    /** The foreground service's demand: page at the background cadence while playback runs. */
    fun setBackgroundDemand(active: Boolean) {
        demands.value = demands.value.copy(background = active)
        ensureLoopStarted()
    }

    /** The iPod's demand: page at the fast cadence while iPod mode is on. */
    fun setFastDemand(active: Boolean) {
        demands.value = demands.value.copy(fast = active)
        ensureLoopStarted()
    }

    // ── Loop lifecycle ───────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    /** Starts the loop if at least one demand is active and it is not already running. */
    @Synchronized
    private fun ensureLoopStarted() {
        if (!demands.value.any) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { pagingLoop() }
    }

    // ── The paging loop ──────────────────────────────────────────────────────

    private suspend fun pagingLoop() {
        // RAW (pre-filter) offset into Spotify's saved-tracks list, tracked across iterations.
        // -1 = seed from the cache's filtered size on the first run.
        var rawOffset = -1
        var prevSize = -1  // last seen cache size, to detect an external reset (shrink)

        _state.value = _state.value.copy(running = true)

        try {
            while (demands.value.any) {
                // Wait THEN fetch (a fresh fast demand gets its first page ~2 s later).
                val interval = if (demands.value.fast) FAST_INTERVAL_MS else BACKGROUND_INTERVAL_MS
                val snapshot = demands.value
                withTimeoutOrNull(interval) {
                    // Wake early if either demand changes (e.g. the iPod turned on mid-background-sleep).
                    demands.first { it != snapshot }
                }

                // Re-check: demands could have gone inactive during the wait.
                if (!demands.value.any) break

                // Rate-limit gate: skip this tick entirely.
                if (playerStateManager.isRateLimited()) continue

                withContext(Dispatchers.IO) {
                    val cached = libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)
                    if (cached == null) {
                        // No liked list in the cache (the Library never opened Liked Songs).
                        // Do nothing this tick; state.total stays null so the iPod knows not to show
                        // an index status. The indexer NEVER creates the list.
                        _state.value = _state.value.copy(cached = null, total = null, complete = false)
                        return@withContext
                    }

                    val total = cached.snapshotId.toIntOrNull()
                    if (total == null) {
                        // The snapshotId is not an int (legacy/corrupt). Nothing useful to do.
                        _state.value = _state.value.copy(cached = cached.tracks.size, total = null, complete = false)
                        return@withContext
                    }

                    val size = cached.tracks.size

                    // Seed on first run; re-seed when the cache shrank under us (a UI-side full
                    // replace/refresh resets the deep cache) so backfill restarts from the new prefix.
                    if (rawOffset < 0 || size < prevSize) rawOffset = size
                    prevSize = size

                    if (rawOffset >= total) {
                        // Fully backfilled (re-arms if total grows — the next tick re-reads the cache).
                        _state.value = _state.value.copy(cached = size, total = total, complete = true)
                        return@withContext
                    }

                    // Update state before the fetch so observers see progress.
                    _state.value = _state.value.copy(cached = size, total = total, complete = false)

                    repository.getLikedSongs(limit = PAGE_SIZE, offset = rawOffset).fold(
                        onSuccess = { resp ->
                            val rawCount = resp.rawCount
                            if (rawCount == 0) {
                                // Past the end.
                                rawOffset = total
                                _state.value = _state.value.copy(cached = size, total = total, complete = true)
                                return@fold
                            }
                            rawOffset += rawCount

                            val newTracks = (resp.items ?: emptyList())
                                .mapNotNull { it.track }
                                .filter { it.isPlayable != false }

                            if (newTracks.isNotEmpty()) {
                                val result = libraryCache.appendToLikedSongs(newTracks, total)
                                if (result != null) {
                                    prevSize = result.rowCount
                                    _state.value = _state.value.copy(
                                        cached = result.rowCount,
                                        total = total,
                                        complete = rawOffset >= total,
                                    )
                                    if (result.added.isNotEmpty()) {
                                        _appended.tryEmit(result.added)
                                    }
                                }
                            }

                            // Short page = last page reached.
                            if (rawCount < PAGE_SIZE) {
                                rawOffset = total
                                _state.value = _state.value.copy(complete = true)
                            }

                            Log.d(TAG, "Indexed page: offset=$rawOffset total=$total cached=${_state.value.cached}")
                        },
                        onFailure = { e ->
                            if (e.message?.contains("429") == true) {
                                playerStateManager.noteRateLimited()
                            }
                        },
                    )
                }
            }
        } finally {
            _state.value = _state.value.copy(running = false)
        }
    }

    companion object {
        const val BACKGROUND_INTERVAL_MS = 30_000L
        const val FAST_INTERVAL_MS = 2_000L
        const val PAGE_SIZE = 50
        private const val TAG = "LikedSongsIndexer"
    }
}
