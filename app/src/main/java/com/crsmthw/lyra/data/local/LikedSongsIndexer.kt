package com.crsmthw.lyra.data.local

import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** The foreground service's demand: page at the background cadence while playback runs. */
    fun setBackgroundDemand(active: Boolean) {
        // Skeleton — implemented by the Checkpoint C data lane.
    }

    /** The iPod's demand: page at the fast cadence while iPod mode is on. */
    fun setFastDemand(active: Boolean) {
        // Skeleton — implemented by the Checkpoint C data lane.
    }

    companion object {
        const val BACKGROUND_INTERVAL_MS = 30_000L
        const val FAST_INTERVAL_MS = 2_000L
        const val PAGE_SIZE = 50
    }
}
