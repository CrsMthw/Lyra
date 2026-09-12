package com.crsmthw.lyra.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.CachedTrackList
import com.crsmthw.lyra.data.local.ForYouCacheData
import com.crsmthw.lyra.data.local.JumpBackInItem
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.*
import com.crsmthw.lyra.data.repository.PartialRemovalException
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import com.crsmthw.lyra.util.MosaicGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which content type the Library browser shows. */
enum class LibraryFilter { PLAYLISTS, ALBUMS, ARTISTS, SHOWS }

data class LibraryUiState(
    val playlists             : List<SpotifyPlaylist>  = emptyList(),
    val forYouEnabled         : Boolean                = false,   // Settings toggle, default off
    val jumpBackIn            : List<JumpBackInItem>   = emptyList(),
    val topTracks             : List<SpotifyTrack>     = emptyList(),
    val libraryFilter         : LibraryFilter          = LibraryFilter.PLAYLISTS,
    val savedAlbums           : List<SpotifyAlbum>     = emptyList(),
    val followedArtists       : List<SpotifyArtist>    = emptyList(),
    val followedShows         : List<SpotifyShow>      = emptyList(),
    val isLoadingCollections  : Boolean                = false,
    val likedSongCount        : Int                    = 0,
    val currentPlaylist       : SpotifyPlaylist?        = null,
    val currentTracks         : List<SpotifyTrack>     = emptyList(),
    val isLoading             : Boolean                = true,
    val isLoadingTracks       : Boolean                = false,
    val isLoadingMoreTracks   : Boolean                = false,
    val isRefreshing          : Boolean                = false,
    val isLibraryRefreshing   : Boolean                = false,
    val likedSongsOffset      : Int                    = 0,
    val likedSongsTotal       : Int                    = 0,
    val playlistTracksOffset  : Int                    = 0,
    val playlistTracksTotal   : Int                    = 0,
    val error                 : String?                = null,  // blocking — shown when no cache
    val refreshError          : String?                = null,  // non-blocking — shown as icon when cache is visible
    val user                  : SpotifyUser?           = null,
    val playlistsWithMosaics  : Set<String>            = emptySet(),
    // ── Multi-select removal (owned playlists only) ──
    val selectionMode         : Boolean                = false,
    val selectedUris          : Set<String>            = emptySet(),
    val isRemovingSelection   : Boolean                = false,  // the batch DELETE is in flight
    val removeResult          : RemoveSelectionResult? = null,   // one-shot; the screen consumes it
)

/**
 * Outcome of a multi-select removal, consumed **once** by `LibraryScreen` — it fires the
 * confirm/reject haptic and, on a failure, shows the error dialog until dismissed. A one-shot in
 * state rather than a haptic fired from the button's `onClick`, because the buzz must report what
 * the API actually did, not what was requested.
 */
sealed interface RemoveSelectionResult {
    data object Success : RemoveSelectionResult
    data class  Failure(val message: String?) : RemoveSelectionResult
}

/**
 * True when the Library is showing a track list (a playlist, or Liked Songs) rather than the
 * browser. `isLoadingTracks` is part of it so a cache MISS still counts as "in the detail" —
 * otherwise back would be dead while the first fetch is in flight.
 */
val LibraryUiState.isShowingDetail: Boolean
    get() = currentPlaylist != null || isLoadingTracks || currentTracks.isNotEmpty()

/**
 * Stable identity of whatever pane the single-pane Library is showing: a playlist id, `"liked"`,
 * or `null` for the browser. This is the single-pane transition's state — see `SinglePaneLayout`,
 * where the seekable (predictive-back-driven) transition animates over THIS rather than over the
 * whole [LibraryUiState], so ordinary data emissions (tracks paginating in, a refresh landing)
 * can't restart or freeze the pane swap.
 */
val LibraryUiState.detailKey: String?
    get() = if (isShowingDetail) (currentPlaylist?.id ?: "liked") else null

/**
 * Drops any in-progress multi-select. The mode belongs to ONE open playlist, so every pane change
 * has to clear it — opening another playlist or Liked Songs, backing out to the browser, or the
 * playlist being deleted underneath it. Also a pull-to-refresh, which isn't a pane change but
 * replaces the track list wholesale back to page 0: a selection that survived it could point at
 * rows no longer on screen, and the button it feeds is a DELETE. Applied on top of the replacing
 * `copy(...)` so the clear lands in the SAME emission as the change (never as an extra one
 * mid-transition).
 */
private fun LibraryUiState.selectionCleared() =
    if (!selectionMode && selectedUris.isEmpty()) this
    else copy(selectionMode = false, selectedUris = emptySet())

/**
 * Commits rows that are gone from the open playlist server-side: drops them from [currentTracks],
 * shrinks the paging counters to match, and reports [result]. Shared by the fully-successful removal
 * and the partial one (a chunked removal whose later chunk failed after earlier chunks committed),
 * so the count arithmetic below exists once. Pure — the caller applies it inside its own
 * `_uiState.update`, so it never adds an emission.
 *
 * This is the INTERIM count, not the authority: it exists so the rows and the "N songs" line move
 * together the instant the API returns. The real total lands a moment later from
 * `LibraryViewModel.reconcilePlaylistTotal` (the server's own `total`), which every in-app mutation
 * schedules.
 */
private fun LibraryUiState.tracksRemoved(
    uris  : Collection<String>,
    result: RemoveSelectionResult,
): LibraryUiState {
    val remaining = currentTracks.filterNot { it.uri in uris }
    // The count delta, not uris.size: a uri can appear twice in a playlist (and remove-by-uri
    // drops every occurrence), so only the list can say how many rows actually went. Offset and
    // total shrink by the same amount, keeping pagination pointed where the API left off.
    val removed   = currentTracks.size - remaining.size
    return copy(
        currentTracks        = remaining,
        playlistTracksOffset = (playlistTracksOffset - removed).coerceAtLeast(remaining.size),
        playlistTracksTotal  = (playlistTracksTotal - removed).coerceAtLeast(remaining.size),
        isRemovingSelection  = false,
        removeResult         = result,
    )
}

class LibraryViewModel(
    private val repository      : SpotifyRepository,
    private val cache           : LibraryCache,
    private val mosaicGenerator : MosaicGenerator,
    private val remoteManager   : SpotifyRemoteManager,
    private val playerStateManager: PlayerStateManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    /** Backs the song touch-and-hold menu for every track list this screen shows. */
    val trackActions = TrackActionsController(repository, cache, viewModelScope)

    /**
     * Removes the long-pressed track from the playlist it's currently shown in (only reachable
     * when that playlist is owned). Drops the row immediately and keeps the cached track list in
     * sync so re-opening the playlist doesn't resurrect it.
     */
    fun removeTrackFromCurrentPlaylist() {
        val target   = trackActions.state.value.target ?: return
        val playlist = target.removable ?: return
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlist.id, target.uri).onSuccess {
                // Route through the single surgical cache method — same path as the picker toggle-off
                // and the player add — so the metadata count and both panes stay in sync. It emits
                // trackListChanges, which refreshes currentTracks via observeTrackListChanges, so no
                // manual list edit is needed here (one path, not two).
                withContext(Dispatchers.IO) {
                    cache.removeFromPlaylistTrackList(playlist.id, target.uri)
                }
                trackActions.dismiss()
            }
        }
    }

    // ── Multi-select removal (owned playlists) ───────────────────────────────────────────────
    // One mode, two doors: the "Select" row in the song touch-and-hold menu (which pre-checks the
    // long-pressed track) and "Select songs" in the detail's floating action pill. Both live only
    // where "Remove from <playlist>" already does — an owned playlist, never Liked Songs or a
    // followed one — so the UI guards on ownership and this guards on there being a playlist at all.

    /** Enters selection mode, with [uri] pre-checked when it came from the song menu. */
    fun enterSelectionMode(uri: String? = null) {
        if (_uiState.value.currentPlaylist == null) return     // Liked Songs isn't editable
        _uiState.update { it.copy(
            selectionMode = true,
            selectedUris  = if (uri != null) setOf(uri) else emptySet(),
        ) }
    }

    /** A tap (or a long-press) on a row while in selection mode: checks / unchecks it. */
    fun toggleTrackSelection(uri: String) {
        _uiState.update { s ->
            if (!s.selectionMode) s
            else s.copy(
                selectedUris = if (uri in s.selectedUris) s.selectedUris - uri else s.selectedUris + uri,
            )
        }
    }

    /** Leaves selection mode without removing anything (Cancel, or the back gesture). */
    fun exitSelectionMode() {
        _uiState.update { it.selectionCleared() }
    }

    /**
     * Batch-removes every checked track from the open playlist in ONE API call (chunked past the
     * API's 100-item cap), then drops the rows and leaves selection mode. A total failure keeps
     * every row and the whole selection; a PARTIAL one (a later chunk failed after earlier chunks
     * already committed server-side) drops what actually went and keeps the mode on the remainder,
     * so the retry sends only what's left. A cancelled removal (the scope dying mid-flight) still
     * reports nothing — the server can be ahead of the cache until the next per-open reconcile.
     */
    fun removeSelectedTracks() {
        val s        = _uiState.value
        val playlist = s.currentPlaylist ?: return
        val uris     = s.selectedUris.toList()
        if (uris.isEmpty() || s.isRemovingSelection) return
        _uiState.update { it.copy(isRemovingSelection = true, removeResult = null) }
        viewModelScope.launch {
            repository.removeTracksFromPlaylist(playlist.id, uris).fold(
                onSuccess = {
                    // The cache call is NOT redundant with the state edit below — it keeps the on-disk
                    // list and the My Playlists count in sync for the next open. It is also not
                    // SUFFICIENT: it no-ops for a playlist that was never cached (no snapshot id, so
                    // selectPlaylist never wrote a track list), and then no trackListChanges emission
                    // would arrive to drop the rows. Hence both, with the state edit authoritative.
                    withContext(Dispatchers.IO) { cache.removeFromPlaylistTrackList(playlist.id, uris) }
                    _uiState.update { st ->
                        if (st.currentPlaylist?.id != playlist.id)
                            return@update st.copy(isRemovingSelection = false)
                        st.tracksRemoved(uris, RemoveSelectionResult.Success).selectionCleared()
                    }
                },
                onFailure = { e ->
                    // A chunked removal can fail PARTWAY: the uris it carries are already gone from
                    // the playlist server-side, so commit them down both paths the success branch
                    // uses before reporting the error — otherwise the dialog claims nothing was
                    // removed while the playlist is already shorter, and the rows survive in the
                    // cache until the next per-open reconcile.
                    val partial = (e as? PartialRemovalException)?.removed?.takeIf { it.isNotEmpty() }
                    if (partial != null) {
                        withContext(Dispatchers.IO) { cache.removeFromPlaylistTrackList(playlist.id, partial) }
                    }
                    _uiState.update { st ->
                        val failure = RemoveSelectionResult.Failure(e.message)
                        // Same "still the same playlist" guard as the success path — the list edit
                        // is playlist-scoped, but the error report isn't, so it's reported either way.
                        if (partial == null || st.currentPlaylist?.id != playlist.id)
                            return@update st.copy(isRemovingSelection = false, removeResult = failure)
                        // Keep the mode on what's left, so a retry sends only the pending uris.
                        st.tracksRemoved(partial, failure).copy(selectedUris = st.selectedUris - partial)
                    }
                },
            )
        }
    }

    /** Consumes [LibraryUiState.removeResult] once the screen has reported it. */
    fun clearRemoveResult() {
        _uiState.update { if (it.removeResult == null) it else it.copy(removeResult = null) }
    }

    /**
     * Deletes an owned playlist (Spotify unfollow). On success removes it from the in-memory list
     * and cache, and closes the detail view if it was the one open. Caller guards that the playlist
     * is owned (never Liked Songs / followed).
     */
    fun deletePlaylist(playlist: SpotifyPlaylist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id).fold(
                onSuccess = {
                    cache.removePlaylist(playlist.id)
                    _uiState.update { s ->
                        val wasOpen = s.currentPlaylist?.id == playlist.id
                        s.copy(
                            playlists       = s.playlists.filterNot { it.id == playlist.id },
                            currentPlaylist = if (wasOpen) null else s.currentPlaylist,
                            currentTracks   = if (wasOpen) emptyList() else s.currentTracks,
                            refreshError    = null,
                        ).let { if (wasOpen) it.selectionCleared() else it }
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(refreshError = e.message) } },
            )
        }
    }

    init {
        _uiState.update { it.copy(playlistsWithMosaics = mosaicGenerator.existingIds()) }
        loadLibrary()
        observeCacheRevision()
        observeTrackListChanges()
        observePlaylistMutations()
        observeForYouSetting()
    }

    // ── Track counts — the authoritative reconcile ────────────────────────────────────────────
    //
    // THE RULE: the server's `total` is the single source of truth for every "N songs" the app
    // shows, and there is exactly ONE writer of it — [applyServerTotal], which writes all three
    // sinks at once (the open detail's `playlistTracksTotal`, the browser/left-pane metadata in
    // `currentPlaylist` + `playlists`, and the cached metadata on disk). A local ±delta is only the
    // interim value that keeps the count moving with the rows; it is never the last word.
    //
    // Why that rule exists: the counts used to be maintained by three independent writers with
    // three different arithmetics — the removal's list delta, the cache's metadata decrement, and
    // this VM's cached-list re-read — none of which ever asked the server. They agree only while
    // the rendered rows, the cached rows and the `/me/playlists` metadata all match, and they do
    // NOT match for any playlist holding items the client filters out (unplayable / local files /
    // episodes / null tracks), so the hero drifted one way and the browser card the other. Only
    // re-opening the playlist or pulling to refresh healed it — because only those asked the server.

    /**
     * One in-flight reconcile per playlist id, so a mutation on playlist A can't cancel B's. The
     * entry is left behind when the job completes — a handful of finished [Job] references, bounded
     * by the number of playlists touched in a session, and [loadMorePlaylistTracks] reads `isActive`
     * on it. Only ever touched from `viewModelScope` (main), so a plain map is enough.
     */
    private val totalReconciles = mutableMapOf<String, Job>()

    /**
     * Answers every in-app add/remove — from this screen's multi-select, the song menu's "Remove
     * from this playlist", the add-to-playlist picker on any screen, or the player — with one
     * authoritative re-read of the playlist's server total.
     */
    /**
     * Playlists mutated in-app this session. Only for these does [loadMorePlaylistTracks] de-dupe an
     * appended page by uri: the artefact it guards against — a page served from Spotify's PRE-mutation
     * list right after a DELETE/POST — can only exist after a mutation, while a uri repeating across
     * pages on an untouched playlist is real content (Spotify's own client adds a duplicate behind an
     * "already added" confirmation; collaborative playlists and imports carry them) that must render
     * twice. Cleared by a pull-to-refresh, which re-reads page 0 from the server.
     */
    private val dedupePagesFor = mutableSetOf<String>()

    /**
     * Playlists showing a prefix seeded from a cache entry with NO recorded raw offset — written
     * before [com.crsmthw.lyra.data.local.CachedTrackList.rawOffset] existed, so the boundary had to
     * fall back to the filtered row count and is short by however many items the client dropped.
     *
     * That makes exactly ONE page suspect: the next one starts inside rows already on screen. So for
     * that page [loadMorePlaylistTracks] de-dupes by uri (a one-page-wide version of what
     * [dedupePagesFor] does for a mutation's stale-page window — deliberately not the session-wide
     * set, which would collapse legitimately duplicated uris on every later page too), and advances
     * the offset by the FULL raw page size with no overlap subtraction: the page really was served
     * from `offset`, so `offset + raw` is the true next position whatever the seed was short by.
     * That re-anchors the offset exactly, so the playlist leaves this set after one page.
     */
    private val untrustedOffsetFor = mutableSetOf<String>()

    private fun observePlaylistMutations() {
        viewModelScope.launch {
            cache.playlistMutations.collect { playlistId ->
                dedupePagesFor += playlistId
                reconcilePlaylistTotal(playlistId)
            }
        }
    }

    /**
     * Debounced `limit = 1` re-read of [playlistId]'s server total, applied to every count sink.
     *
     * The debounce both coalesces a burst of mutations (each cancels the previous job) and gives
     * Spotify a beat to become read-after-write consistent: a `GET` issued immediately after a
     * `DELETE` can still answer with the pre-removal total, and writing that back would undo the
     * optimistic edit the user is looking at. One attempt, no retry loop — if a stale read does land
     * it is a wrong count until the next mutation, open, or refresh, never a permanently wrong one.
     */
    private fun reconcilePlaylistTotal(playlistId: String) {
        totalReconciles[playlistId]?.cancel()
        totalReconciles[playlistId] = viewModelScope.launch {
            delay(TOTAL_RECONCILE_DEBOUNCE_MS)
            repository.getPlaylistTracks(playlistId, limit = 1, offset = 0).onSuccess { resp ->
                applyServerTotal(playlistId, resp.total)
            }
        }
    }

    /**
     * The ONE writer of an authoritative playlist total. Writes it to the open detail's
     * `playlistTracksTotal` (so the hero's "N songs" is right), to the playlist metadata held in
     * state (so the two-pane left card and the single-pane browser card update with no manual
     * refresh) and to the cache (so the card is right on the next launch too) — all in a single
     * `_uiState.update`, and never touching `currentPlaylist?.id`, so `detailKey` is unchanged and
     * the pane transition can't see this (docs/MOTION.md → Predictive back).
     *
     * The loaded row count is a FLOOR, never a ceiling: a lagging server total can't drag the count
     * below rows we can actually see, but it is allowed to be higher — rendered rows are the
     * filtered list, so their number is not a valid total (see the filtering note above).
     */
    private suspend fun applyServerTotal(playlistId: String, serverTotal: Int) {
        val current = _uiState.value
        val total   = if (current.currentPlaylist?.id == playlistId)
                          maxOf(serverTotal, current.currentTracks.size) else serverTotal
        // The in-memory list mirrors the cached one (every writer of the cached metadata bumps the
        // revision this VM re-syncs from), so it can answer "does the disk already say this?" —
        // worth asking, because setPlaylistTrackCount re-parses the WHOLE cache file to find out,
        // and on the per-open and pull-to-refresh paths the answer is normally yes.
        val diskNeedsIt = current.playlists.any { it.id == playlistId && it.trackCount != total }
        _uiState.update { s ->
            val open = s.currentPlaylist?.takeIf { it.id == playlistId }
            s.copy(
                playlistTracksTotal = if (open != null) total else s.playlistTracksTotal,
                currentPlaylist     = open?.withTrackCount(total) ?: s.currentPlaylist,
                playlists           = s.playlists.withTrackCount(playlistId, total),
            )
        }
        if (diskNeedsIt) withContext(Dispatchers.IO) { cache.setPlaylistTrackCount(playlistId, total) }
    }

    /**
     * Mirrors the Settings "For you band" toggle into UI state (gates the band's sections) and,
     * when it's switched ON mid-session, fires the fetch the gated [loadForYou] skipped at load
     * time so the band populates without a manual refresh. Only a genuine off→on transition
     * triggers the fetch — the first emission is start-up state, where loadLibrary owns the load.
     */
    private fun observeForYouSetting() {
        viewModelScope.launch {
            var previous: Boolean? = null
            settingsRepository.forYouEnabled.collect { enabled ->
                _uiState.update { it.copy(forYouEnabled = enabled) }
                if (previous == false && enabled) loadForYou()
                previous = enabled
            }
        }
    }

    /**
     * Live-refreshes the OPEN playlist's track list when its cached list changes from elsewhere —
     * e.g. adding/removing the current track via the full player or pop-out add-to-playlist sheet,
     * or the song-menu picker on another screen. The signal carries the edit itself
     * ([com.crsmthw.lyra.data.local.TrackListChange]), so the change shows without a manual
     * pull-to-refresh. No-op unless the changed playlist is open.
     *
     * It APPLIES that edit to the rows on screen. It never diffs them against the cached list, and
     * never reads the cache at all — the two can legitimately differ by rows this edit had nothing
     * to do with. See below.
     */
    private fun observeTrackListChanges() {
        viewModelScope.launch {
            cache.trackListChanges.collect { change ->
                if (_uiState.value.currentPlaylist?.id != change.playlistId) return@collect
                _uiState.update { s ->
                    if (s.currentPlaylist?.id != change.playlistId) return@update s
                    // Move the counters by the rows that ACTUALLY left or joined THIS list — the
                    // uris the edit itself carries — and never by anything derived from the cached
                    // list's contents or SIZE.
                    //
                    // Neither list size is a counter to begin with: `currentTracks` and the cached
                    // rows are both the FILTERED list (unplayable, local files, episodes and null
                    // tracks are dropped client-side) while `offset`/`total` count every item the
                    // API returns, so adopting a size throws that gap away (bugs 36/37). A size
                    // DIFFERENCE is no safer — and neither is a set difference against the cache:
                    // both book any pre-existing gap between the rendered rows and the cached ones
                    // onto this one edit. With the cache holding a row FEWER than the UI (a page
                    // the UI has that the cache never got), a set difference deletes that rendered
                    // row and drags the hero below the rows that really went, until the server's
                    // total lands ~600 ms later (checklist 21/23). Hence the carried mutation.
                    //
                    // So: drop exactly the uris the caller asked Spotify to remove (every
                    // occurrence, as remove-by-uri does), append the one it added at the end (where
                    // Spotify puts it), and derive the delta from THIS list's own before/after size.
                    // Cache/UI drift now moves nothing. Still only the INTERIM value: the same cache
                    // call that emitted here also scheduled the authoritative server reconcile.
                    val shown = s.currentTracks
                    val left  = if (change.removedUris.isEmpty()) shown
                                else shown.filterNot { it.uri in change.removedUris }
                    // Identity when the removal matched no rendered row, so an already-applied edit
                    // (the multi-select removal edits the state itself, then this collector runs
                    // over the same change) keeps the SAME list instance.
                    val kept  = if (left.size == shown.size) shown else left
                    // Belt-and-braces, and not redundant: `appendToPlaylistTrackList` only refuses a
                    // track the CACHE already holds, so this guards the case that is this signal's
                    // whole subject — the rows on screen and the cached ones having drifted.
                    val added = change.added?.takeIf { t -> kept.none { it.uri == t.uri } }
                    val newTracks = if (added == null) kept else kept + added
                    // Nothing to apply → the SAME state object, which StateFlow conflates, so no
                    // extra emission. Both orderings of a multi-select removal (which edits the
                    // state itself AND calls the cache) therefore land on the same number: collector
                    // first → delta −3 → 128 becomes 125, then `tracksRemoved` finds 0 rows left to
                    // drop and moves nothing; `tracksRemoved` first → 125, then this filter matches
                    // nothing → identity → conflated. The partial-failure branch is the same shape
                    // over its committed chunk, and the `selectedUris` intersect below composes with
                    // its `- partial` in either order. The one extra emission the collector-first
                    // ordering can add is harmless: neither writer touches `currentPlaylist?.id`, so
                    // `detailKey` never moves and the single-pane seekable transition cannot see it
                    // (docs/MOTION.md → Predictive back).
                    if (newTracks === shown) return@update s
                    val delta = newTracks.size - shown.size
                    s.copy(
                        currentTracks        = newTracks,
                        playlistTracksOffset = (s.playlistTracksOffset + delta).coerceAtLeast(newTracks.size),
                        playlistTracksTotal  = (s.playlistTracksTotal + delta).coerceAtLeast(newTracks.size),
                        // Unlike a pull-to-refresh this is someone else's edit landing, so it keeps
                        // any in-progress multi-select rather than wiping it — but it intersects it
                        // with the new list, so a selection can never point at rows that are gone
                        // (the removal button reads the raw set, so an off-list uri would be an
                        // invisible DELETE and would make the row-count delta compute 0). An emptied
                        // set leaves a "0 selected" pill with Remove already disabled.
                        selectedUris         = if (s.selectedUris.isEmpty()) s.selectedUris
                                               else s.selectedUris intersect
                                                    newTracks.mapTo(HashSet()) { it.uri },
                    )
                }
            }
        }
    }

    /**
     * Re-syncs the in-memory playlist list from the cache whenever a playlist is created (or
     * deleted) from elsewhere — e.g. creating a new playlist from the song touch-and-hold menu or
     * the player's add-to-playlist sheet while this screen is already in memory. Without this the
     * new playlist only appears after a manual pull-to-refresh. The cache revision never fires on a
     * plain library save, so a normal refresh doesn't double-load.
     */
    private fun observeCacheRevision() {
        viewModelScope.launch {
            cache.revision.drop(1).collect {
                val cached = withContext(Dispatchers.IO) { cache.load() } ?: return@collect
                _uiState.update { it.copy(
                    playlists       = cached.playlists,
                    savedAlbums     = cached.savedAlbums.orEmpty(),
                    followedArtists = cached.followedArtists.orEmpty(),
                    followedShows   = cached.followedShows.orEmpty(),
                ) }
            }
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, refreshError = null) }

            // Show cached data immediately so the screen is never blank
            val cached = withContext(Dispatchers.IO) { cache.load() }
            val hasCache = cached != null && cached.playlists.isNotEmpty()
            if (cached != null && hasCache) {
                _uiState.update { it.copy(
                    playlists         = cached.playlists,
                    jumpBackIn        = cached.forYou?.jumpBackIn.orEmpty(),
                    topTracks         = cached.forYou?.topTracks.orEmpty(),
                    savedAlbums       = cached.savedAlbums.orEmpty(),
                    followedArtists   = cached.followedArtists.orEmpty(),
                    followedShows     = cached.followedShows.orEmpty(),
                    likedSongCount    = cached.likedSongCount,
                    user              = cached.user,
                    isLoading         = false,
                )}
                // Generate mosaics for playlists with cached track lists but no mosaic yet
                generateMissingMosaicsAsync(cached.playlists, cached.trackLists)
            } else {
                _uiState.update { it.copy(isLoading = true) }
            }

            // Refresh from network (silently if we have cached data)
            repository.getCurrentUser().fold(
                onSuccess = { user -> _uiState.update { it.copy(user = user) } },
                onFailure = { },
            )

            val playlistsResult = repository.getUserPlaylists()
            if (playlistsResult.isFailure) {
                val e = playlistsResult.exceptionOrNull()
                if (!e.isTransientNetworkError()) {
                    val msg = e?.message
                    if (hasCache) {
                        _uiState.update { it.copy(refreshError = msg) }
                    } else {
                        _uiState.update { it.copy(error = msg, isLoading = false) }
                    }
                }
                return@launch
            }
            _uiState.update { it.copy(playlists = playlistsResult.getOrThrow().items) }

            repository.getLikedSongs(limit = 1).fold(
                onSuccess = { resp -> _uiState.update { it.copy(likedSongCount = resp.total) } },
                onFailure = { },
            )

            _uiState.update { it.copy(isLoading = false) }

            loadForYou()   // after playlists so jump-back-in context lookup can resolve

            // Persist refreshed data. ONE patching write, never a read-then-replace: the track
            // lists, the For-you band and the collection lists are left on disk untouched, so a
            // page append or a surgical add/remove landing mid-refresh can't be reverted under us.
            val s = _uiState.value
            withContext(Dispatchers.IO) {
                cache.saveLibraryMeta(s.playlists, s.likedSongCount, s.user)
            }
            // Generate mosaics for any new playlists that now have cached track lists
            generateMissingMosaicsAsync(s.playlists, cache.load()?.trackLists ?: emptyMap())
        }
    }

    /**
     * Loads the "For you" band: jump-back-in (de-duped recently-played contexts) + short-term top
     * tracks. Runs after the playlist lists are in state so playlist contexts can resolve to a
     * name/art without extra calls; playlist contexts that aren't in the library are skipped
     * (naming them would cost a metadata call each). Failures keep the cached band silently.
     */
    private fun loadForYou() {
        viewModelScope.launch {
            // Read the persisted toggle directly (not the state mirror, which may not have
            // emitted yet at start-up): band off → no recently-played/top-tracks calls at all.
            if (!settingsRepository.forYouEnabled.first()) return@launch
            repository.getRecentlyPlayed(limit = 50).onSuccess { resp ->
                _uiState.update { it.copy(jumpBackIn = buildJumpBackIn(resp, _uiState.value.playlists)) }
            }
            repository.getTopTracks(timeRange = "short_term", limit = 20).onSuccess { page ->
                _uiState.update { it.copy(topTracks = page.items) }
            }
            val s = _uiState.value
            if (s.jumpBackIn.isNotEmpty() || s.topTracks.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    cache.saveForYou(ForYouCacheData(jumpBackIn = s.jumpBackIn, topTracks = s.topTracks))
                }
            }
        }
    }

    private fun buildJumpBackIn(
        resp      : RecentlyPlayedResponse,
        playlists : List<SpotifyPlaylist>,
    ): List<JumpBackInItem> {
        val items = mutableListOf<JumpBackInItem>()
        val seen  = mutableSetOf<String>()
        for (h in resp.items.orEmpty()) {
            if (items.size >= MAX_JUMP_BACK_IN) break
            val ctx = h.context ?: continue
            val uri = ctx.uri ?: continue
            if (!seen.add(uri)) continue
            val id = ctx.contextId ?: continue
            when {
                // Liked Songs plays as the "collection" context (spotify:user:<id>:collection).
                uri.endsWith(":collection") ->
                    items += JumpBackInItem(type = "liked", id = "liked", uri = uri, title = "liked")
                ctx.type == "playlist" -> {
                    val pl = playlists.firstOrNull { it.id == id } ?: continue
                    items += JumpBackInItem(
                        type   = "playlist",
                        id     = pl.id,
                        uri    = pl.uri,
                        title  = pl.name,
                        artUrl = pl.thumbnailUrl.takeIf { it.isNotBlank() },
                    )
                }
                ctx.type == "album" -> {
                    val track = h.track ?: continue
                    val name  = track.album?.name ?: continue
                    items += JumpBackInItem(
                        type     = "album",
                        id       = id,
                        uri      = uri,
                        title    = name,
                        subtitle = track.allArtists,
                        artUrl   = track.artUrl.takeIf { it.isNotBlank() },
                    )
                }
                ctx.type == "artist" -> {
                    val track  = h.track ?: continue
                    val artist = track.artists?.firstOrNull() ?: continue
                    items += JumpBackInItem(
                        type   = "artist",
                        id     = artist.id,
                        uri    = uri,
                        title  = artist.name,
                        artUrl = track.artUrl.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        // Fallback fill: many recently-played items carry NO context (queue/search/autoplay plays,
        // and some App-Remote plays) — without this the row can come up empty even with heavy
        // listening. Fill remaining slots with the played tracks' albums, de-duped against both
        // the context items above and each other.
        if (items.size < MAX_JUMP_BACK_IN) {
            for (h in resp.items.orEmpty()) {
                if (items.size >= MAX_JUMP_BACK_IN) break
                val track = h.track ?: continue
                val album = track.album ?: continue
                val uri   = "spotify:album:${album.id}"
                if (!seen.add(uri)) continue
                items += JumpBackInItem(
                    type     = "album",
                    id       = album.id,
                    uri      = uri,
                    title    = album.name,
                    subtitle = track.allArtists,
                    artUrl   = track.artUrl.takeIf { it.isNotBlank() },
                )
            }
        }
        return items
    }

    // ── Library filter (Playlists / Albums / Artists / Shows) ────────────────

    // ── Albums / Artists / Shows: once-per-session network refresh, PER LEG ──────────────────────
    //
    // Each leg is marked loaded only when its paginated sweep ran to COMPLETION — the loop exiting
    // on `page.next == null || items.isEmpty()`. A sweep that stopped because a page came back null
    // (a 429 on the third back-to-back sweep is the likely one) leaves its flag false, so the next
    // segment tap — or a pull-to-refresh while a non-Playlists filter is showing — re-fetches just
    // that leg while the complete ones stay put. One shared flag used to mean a single failed leg
    // rendered its empty state for the rest of the session, with no way back but a restart.
    //
    // "Did it come back empty?" is NOT the same question and must not stand in for it: an empty
    // library completes legitimately, and a sweep that died on page 2 with 100 rows in hand is
    // truncated, not done — persisting that would overwrite a complete cached list with a prefix.
    private var albumsLoaded  = false
    private var artistsLoaded = false
    private var showsLoaded   = false

    /**
     * Re-entry guard, flipped on the main thread BEFORE the launch: the per-leg flags above are
     * only settled inside the coroutine, so without this two rapid segment taps would start two
     * concurrent sweeps.
     */
    private var collectionsInFlight = false

    fun setLibraryFilter(filter: LibraryFilter) {
        _uiState.update { it.copy(libraryFilter = filter) }
        // Cached content (if any) is already in state from loadLibrary; refresh from the network
        // the first time each non-playlist filter's content is needed this session.
        if (filter != LibraryFilter.PLAYLISTS) loadCollections()
    }

    private fun loadCollections() {
        if (collectionsInFlight) return
        if (albumsLoaded && artistsLoaded && showsLoaded) return
        collectionsInFlight = true
        viewModelScope.launch {
            // A leg that is skipped (already complete) or that failed leaves its `…Complete` false,
            // so the persist step below passes null for it and the cache keeps what it has.
            var albumsComplete  = false
            var artistsComplete = false
            var showsComplete   = false
            try {
                _uiState.update { it.copy(isLoadingCollections = true) }

                // Saved albums — offset-paged.
                if (!albumsLoaded) {
                    val albums = mutableListOf<SpotifyAlbum>()
                    var offset = 0
                    while (true) {
                        val page = repository.getSavedAlbums(limit = 50, offset = offset).getOrNull()
                        if (page == null) break                  // partial or failed — retry later
                        val items = page.items.orEmpty()
                        albums += items.mapNotNull { it.album }
                        offset += items.size
                        if (page.next == null || items.isEmpty()) { albumsComplete = true; break }
                    }
                    // Show a partial sweep's rows too — better than an empty grid — but only a
                    // complete one is remembered or persisted.
                    if (albumsComplete || albums.isNotEmpty()) _uiState.update { it.copy(savedAlbums = albums) }
                    albumsLoaded = albumsComplete
                }

                // Followed artists — cursor-paged, nested under "artists".
                if (!artistsLoaded) {
                    val artists = mutableListOf<SpotifyArtist>()
                    var after: String? = null
                    while (true) {
                        val page = repository.getFollowedArtists(after).getOrNull()?.artists
                        if (page == null) break
                        val items = page.items.orEmpty()
                        artists += items
                        after = page.cursors?.after
                        if (after == null || items.isEmpty()) { artistsComplete = true; break }
                    }
                    if (artistsComplete || artists.isNotEmpty()) _uiState.update { it.copy(followedArtists = artists) }
                    artistsLoaded = artistsComplete
                }

                // Followed podcasts — offset-paged like albums. `me/shows` is the only read side;
                // the per-show follow toggle lives on the show detail screen and patches the cache
                // surgically (LibraryCache.add/removeFollowedShow), same as albums and artists.
                if (!showsLoaded) {
                    val shows = mutableListOf<SpotifyShow>()
                    var showOffset = 0
                    while (true) {
                        val page = repository.getSavedShows(limit = 50, offset = showOffset).getOrNull()
                        if (page == null) break
                        val items = page.items.orEmpty()
                        // `episodes = null` as insurance: `me/shows` returns the SIMPLIFIED show
                        // object (no embedded episodes page), but the cache file is read whole on
                        // every library paint, so a shape change that started embedding 50 episodes
                        // per show must not silently bloat it. Mirrors ShowDetailViewModel's write.
                        shows += items.mapNotNull { it.show?.copy(episodes = null) }
                        showOffset += items.size
                        if (page.next == null || items.isEmpty()) { showsComplete = true; break }
                    }
                    if (showsComplete || shows.isNotEmpty()) _uiState.update { it.copy(followedShows = shows) }
                    showsLoaded = showsComplete
                }

                // Persist COMPLETE legs only — null leaves the cached list alone, so a truncated
                // sweep can never overwrite a whole one and a skipped leg can never blank it.
                if (albumsComplete || artistsComplete || showsComplete) {
                    val s = _uiState.value
                    withContext(Dispatchers.IO) {
                        cache.saveCollections(
                            savedAlbums     = s.savedAlbums.takeIf     { albumsComplete },
                            followedArtists = s.followedArtists.takeIf { artistsComplete },
                            followedShows   = s.followedShows.takeIf   { showsComplete },
                        )
                    }
                }
            } finally {
                collectionsInFlight = false
                _uiState.update { it.copy(isLoadingCollections = false) }
            }
        }
    }

    fun refreshLibrary() {
        if (_uiState.value.isLibraryRefreshing) return
        _uiState.update { it.copy(isLibraryRefreshing = true, refreshError = null) }
        viewModelScope.launch {
            repository.getCurrentUser().fold(
                onSuccess = { user -> _uiState.update { it.copy(user = user) } },
                onFailure = { },
            )

            val playlistsResult = repository.getUserPlaylists()
            if (playlistsResult.isFailure) {
                val e = playlistsResult.exceptionOrNull()
                if (!e.isTransientNetworkError()) {
                    _uiState.update { it.copy(refreshError = e?.message) }
                }
                _uiState.update { it.copy(isLibraryRefreshing = false) }
                return@launch
            }
            _uiState.update { it.copy(playlists = playlistsResult.getOrThrow().items) }

            repository.getLikedSongs(limit = 1).fold(
                onSuccess = { resp -> _uiState.update { it.copy(likedSongCount = resp.total) } },
                onFailure = { },
            )

            _uiState.update { it.copy(isLibraryRefreshing = false) }

            loadForYou()

            // Give a leg that failed earlier this session another go — a pull-to-refresh on an
            // empty Shows/Albums/Artists grid is exactly the gesture for it, and `loadCollections`
            // returns immediately for the legs that already completed. Gated on the visible filter
            // so refreshing Playlists never kicks off sweeps the user hasn't asked for.
            if (_uiState.value.libraryFilter != LibraryFilter.PLAYLISTS) loadCollections()

            // Same patching write as loadLibrary — see the note there.
            val s = _uiState.value
            withContext(Dispatchers.IO) {
                cache.saveLibraryMeta(s.playlists, s.likedSongCount, s.user)
            }
            generateMissingMosaicsAsync(s.playlists, cache.load()?.trackLists ?: emptyMap())
        }
    }

    private fun generateMissingMosaicsAsync(
        playlists  : List<SpotifyPlaylist>,
        trackLists : Map<String, CachedTrackList>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            for (playlist in playlists) {
                if (playlist.id in _uiState.value.playlistsWithMosaics) continue
                if (playlist.thumbnailUrl.isNotBlank()) continue
                val trackList = trackLists[playlist.id] ?: continue
                if (playlist.snapshotId != null && trackList.snapshotId != playlist.snapshotId) continue
                if (mosaicGenerator.generate(playlist.id, trackList.tracks)) {
                    _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                }
            }
        }
    }

    /** Tracks the playlist the user most recently tapped, so the async cache read below can bail when
     *  a newer tap supersedes it (currentPlaylist isn't set until the detail is content-ready, so the
     *  usual `currentPlaylist?.id` in-flight guard can't be used during the cache read). */
    private var pendingSelectionId: String? = null

    fun selectPlaylist(playlist: SpotifyPlaylist) {
        if (_uiState.value.currentPlaylist?.id == playlist.id || pendingSelectionId == playlist.id) return
        // Read the cached tracks BEFORE flipping to the detail, so the browser→detail container
        // transform starts content-ready: no empty first frame, and no track emission landing
        // mid-transition (a state change during the crossfade is what blanks the screen). The browser
        // simply stays up for the ~ms disk read. Mirrors selectLikedSongs. A genuine cache miss flips
        // to the empty loading detail and the network fills it AFTER the transition (so it can't blank).
        pendingSelectionId = playlist.id
        viewModelScope.launch {
            val snapshotId = playlist.snapshotId
            val cached = if (snapshotId != null)
                withContext(Dispatchers.IO) { cache.loadTrackList(playlist.id) } else null
            if (pendingSelectionId != playlist.id) return@launch   // a newer selection superseded this

            if (cached != null && cached.snapshotId == snapshotId) {
                // Snapshot matches → cache is current. Flip to the detail in ONE content-ready emission
                // (tracks present from the transition's first frame); seed pagination from it (whatever
                // pages were loaded+cached before are preserved — no page-0 refetch that would clobber).
                _uiState.update { it.copy(
                    currentPlaylist      = playlist,
                    currentTracks        = cached.tracks,
                    isLoadingTracks      = false,
                    // The RAW offset the cache recorded, NOT the row count: the cached rows are the
                    // filtered ones (unplayable / local / episode / null items are dropped), so on a
                    // playlist holding k of them `tracks.size` is k short of the API position and the
                    // next page re-fetches — and re-renders — k rows already on screen (checklist 24).
                    playlistTracksOffset = cached.rawOffset ?: cached.tracks.size,
                    playlistTracksTotal  = maxOf(playlist.trackCount, cached.tracks.size),
                    error                = null,
                ).selectionCleared() }
                // Legacy entry, written before the cache recorded a raw offset: the boundary above is
                // the old guess, so the next page needs the one-page de-dupe and re-anchor — see
                // [untrustedOffsetFor].
                if (cached.rawOffset == null) untrustedOffsetFor += playlist.id
                else                          untrustedOffsetFor -= playlist.id
                if (playlist.id !in _uiState.value.playlistsWithMosaics && playlist.thumbnailUrl.isBlank()) {
                    withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, cached.tracks) }
                    _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                }
                // Reconcile the authoritative total once per open (limit=1): heals a drifted count —
                // an in-app add that Spotify's /me/playlists metadata hasn't caught up on, or a
                // change made on another device — without a manual refresh. Same single writer the
                // post-mutation reconcile uses, so "open" and "just edited" can't disagree.
                repository.getPlaylistTracks(playlist.id, limit = 1, offset = 0).onSuccess { resp ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@onSuccess
                    applyServerTotal(playlist.id, resp.total)
                }
                return@launch
            }

            // No cache or stale snapshot — flip to a loading detail (hero + spinner), then fetch the
            // first page. The fetch lands after the transition, so the empty start doesn't blank.
            _uiState.update { it.copy(
                currentPlaylist      = playlist,
                isLoadingTracks      = true,
                currentTracks        = emptyList(),
                playlistTracksOffset = 0,
                playlistTracksTotal  = playlist.trackCount,   // metadata total; refined from the response
                error                = null,
            ).selectionCleared() }
            repository.getPlaylistTracks(playlist.id).fold(
                onSuccess = { resp ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@fold
                    val tracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                    _uiState.update { it.copy(
                        currentTracks        = tracks,
                        isLoadingTracks      = false,
                        // Offset advances by the RAW page size (incl. filtered-out items) so the next
                        // page picks up where the API left off — avoids re-fetch/duplicates when a
                        // playlist contains unplayable tracks.
                        playlistTracksOffset = resp.items?.size ?: 0,
                        playlistTracksTotal  = resp.total,
                    ) }
                    untrustedOffsetFor -= playlist.id   // this page anchored the offset on the API's own position
                    if (snapshotId != null) {
                        val rawOffset = resp.items?.size ?: 0
                        withContext(Dispatchers.IO) {
                            cache.saveTrackList(playlist.id, snapshotId, tracks, rawOffset)
                        }
                    }
                    if (playlist.thumbnailUrl.isBlank()) {
                        withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, tracks) }
                        _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                    }
                },
                onFailure = { e ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@fold
                    val msg = if (e.message?.contains("403") == true)
                        "Track list unavailable for this playlist. You can still play it with the ▶ button."
                    else e.message
                    _uiState.update { it.copy(error = msg, isLoadingTracks = false) }
                },
            )
        }
    }

    fun clearSelection() {
        pendingSelectionId = null   // cancel any in-flight playlist cache read
        _uiState.update { it.copy(
            currentPlaylist     = null,
            currentTracks       = emptyList(),
            isLoadingTracks     = false,
            isLoadingMoreTracks = false,
            likedSongsOffset    = 0,
            likedSongsTotal     = 0,
            error               = null,
        ).selectionCleared() }
    }

    fun playPlaylist(uri: String) {
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                } else {
                    playerStateManager.releasePlayingOptimism()
                }
            }
        }
    }

    fun shufflePlaylist(uri: String) {
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.setShuffle(true)
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                } else {
                    playerStateManager.releasePlayingOptimism()
                }
            }
        }
    }

    fun selectLikedSongs() {
        // Already on Liked (no playlist + tracks loaded), or a Liked load already in flight → no-op.
        if ((_uiState.value.currentPlaylist == null && _uiState.value.currentTracks.isNotEmpty()) ||
            pendingSelectionId == LibraryCache.LIKED_SONGS_KEY) return
        // Read the liked cache BEFORE flipping to the list, so the swap starts content-ready — no empty
        // placeholder first frame and no track emission landing mid-transition (which blanks the
        // two-pane swap). The current view stays for the ~ms disk read. Mirrors selectPlaylist.
        pendingSelectionId = LibraryCache.LIKED_SONGS_KEY
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY) }
            if (pendingSelectionId != LibraryCache.LIKED_SONGS_KEY) return@launch   // superseded by a newer selection

            if (cached != null) {
                // Show cached tracks immediately — no loading spinner for the user.
                // distinctBy { it.id }: self-heal a cache that an older build corrupted with duplicate
                // liked songs (the filtered-offset pagination bug). Re-persisted below so the dupes
                // are gone on disk too — no clear-storage needed.
                val cachedCount   = cached.snapshotId.toIntOrNull() ?: cached.tracks.size
                val cachedTracks  = cached.tracks.distinctBy { it.id }
                _uiState.update { it.copy(
                    currentPlaylist     = null,
                    currentTracks       = cachedTracks,
                    isLoadingTracks     = false,
                    likedSongsOffset    = cachedTracks.size,
                    likedSongsTotal     = cachedCount,
                    isLoadingMoreTracks = false,
                    error               = null,
                ).selectionCleared() }
                if (cachedTracks.size != cached.tracks.size) {
                    withContext(Dispatchers.IO) {
                        cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, cachedCount.toString(), cachedTracks)
                    }
                }

                // Background count check — detects external likes/unlikes since last open.
                repository.getLikedSongs(limit = 1).fold(
                    onSuccess = { resp ->
                        val newTotal = resp.total
                        _uiState.update { it.copy(likedSongCount = newTotal) }
                        val diff = newTotal - cachedCount
                        when {
                            diff in 1..50 -> {
                                // Songs added externally: fetch only the new ones and prepend.
                                repository.getLikedSongs(limit = diff, offset = 0).fold(
                                    onSuccess = { newResp ->
                                        val newTracks = (newResp.items ?: emptyList())
                                            .mapNotNull { it.track }
                                            .filter { it.isPlayable != false }
                                        // distinctBy guards against an "external add" that's actually
                                        // a track already in the cache (e.g. re-like of an existing one).
                                        val merged = (newTracks + cachedTracks).distinctBy { it.id }
                                        _uiState.update { s -> s.copy(
                                            currentTracks    = merged,
                                            likedSongsOffset = merged.size,
                                            likedSongsTotal  = newTotal,
                                        )}
                                        withContext(Dispatchers.IO) {
                                            cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, newTotal.toString(), merged)
                                        }
                                    },
                                    onFailure = { fetchAndReplaceLikedSongs() },
                                )
                            }
                            diff != 0 -> fetchAndReplaceLikedSongs()  // decreased or jumped >50
                        }
                    },
                    onFailure = { },  // network unavailable — keep showing cache
                )
                return@launch
            }

            // No cache — flip to a loading detail (Liked gradient hero + spinner), then fetch. The
            // network lands after the transition, so the empty start doesn't blank.
            _uiState.update { it.copy(
                currentPlaylist     = null,
                currentTracks       = emptyList(),
                isLoadingTracks     = true,
                likedSongsOffset    = 0,
                likedSongsTotal     = 0,
                isLoadingMoreTracks = false,
                error               = null,
            ).selectionCleared() }
            fetchAndReplaceLikedSongs()
        }
    }

    private suspend fun fetchAndReplaceLikedSongs() {
        repository.getLikedSongs(limit = 50, offset = 0).fold(
            onSuccess = { resp ->
                val tracks = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                _uiState.update { it.copy(
                    currentTracks    = tracks,
                    isLoadingTracks  = false,
                    // RAW page size, not filtered: see loadMoreLikedSongs. A null track on page 0
                    // would otherwise short the offset and overlap the next page.
                    likedSongsOffset = resp.items?.size ?: tracks.size,
                    likedSongsTotal  = resp.total,
                    likedSongCount   = resp.total,
                )}
                withContext(Dispatchers.IO) {
                    cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), tracks)
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = e.message, isLoadingTracks = false) }
            },
        )
    }

    fun loadMoreLikedSongs() {
        val s = _uiState.value
        // Same initial-load race guard as loadMorePlaylistTracks (see there).
        if (s.isLoadingTracks || s.isLoadingMoreTracks || s.likedSongsOffset >= s.likedSongsTotal) return
        _uiState.update { it.copy(isLoadingMoreTracks = true) }
        viewModelScope.launch {
            repository.getLikedSongs(limit = 50, offset = s.likedSongsOffset).fold(
                onSuccess = { resp ->
                    val newTracks = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                    // Advance the offset by the RAW page size, not the post-filter size: Spotify's
                    // offset indexes every saved item, including removed-from-Spotify tracks (null
                    // track) that mapNotNull drops. Advancing by the filtered size under-counts and
                    // makes the next page overlap → duplicate rows. distinctBy heals any overlap left
                    // from a cache-resume start offset. (Playlists already do this — see loadMorePlaylistTracks.)
                    val allTracks = (s.currentTracks + newTracks).distinctBy { it.id }
                    _uiState.update { it.copy(
                        currentTracks       = allTracks,
                        isLoadingMoreTracks = false,
                        likedSongsOffset    = s.likedSongsOffset + (resp.items?.size ?: 0),
                        likedSongsTotal     = resp.total,
                    )}
                    withContext(Dispatchers.IO) {
                        cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), allTracks)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMoreTracks = false) }
                },
            )
        }
    }

    /** Lazy-loads the next page of the open playlist's tracks (mirrors [loadMoreLikedSongs]). */
    fun loadMorePlaylistTracks() {
        val s = _uiState.value
        val playlist = s.currentPlaylist ?: return
        // isLoadingTracks guard: the TrackList auto-fires onLoadMore as soon as the (briefly empty)
        // list "reaches bottom", which collides with selectPlaylist's async cache load — loadMore
        // would fetch page 0 and append it onto the just-loaded cached list, doubling it (and
        // persisting the doubled list, so it compounds every open). Don't paginate until the initial
        // load has settled.
        if (s.isLoadingTracks || s.isLoadingMoreTracks || s.playlistTracksOffset >= s.playlistTracksTotal) return
        _uiState.update { it.copy(isLoadingMoreTracks = true) }
        viewModelScope.launch {
            repository.getPlaylistTracks(playlist.id, limit = 50, offset = s.playlistTracksOffset).fold(
                onSuccess = { resp ->
                    val live = _uiState.value
                    if (live.currentPlaylist?.id != playlist.id) {
                        _uiState.update { it.copy(isLoadingMoreTracks = false) }
                        return@fold
                    }
                    val newTracks   = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                    val rawPageSize = resp.items?.size ?: 0
                    // De-dupe the page against what's already loaded, by uri — but ONLY for a playlist
                    // this session mutated in-app (`dedupePagesFor`). The artefact: a page fetched
                    // moments after a removal can still be served from Spotify's PRE-removal list,
                    // where our loaded prefix ends `removed` items earlier, so the page starts inside
                    // rows we already have. `TrackList` keys rows on "<uri>#<ordinal>", so such a row
                    // really renders, and `selectedUris` is a uri SET — which is why checking one of
                    // the twins checked both, and why removing it then over-counted the rows that went.
                    // An UNTOUCHED playlist is never de-duped: a uri that legitimately appears twice
                    // (Spotify's client adds behind an "already added" prompt; collaborative playlists)
                    // must keep both rows — that is what the ordinal in the row key exists for.
                    // The same de-dupe covers this page when the offset it was fetched at came from a
                    // legacy cache entry's guessed boundary — see [untrustedOffsetFor].
                    val untrustedOffset = playlist.id in untrustedOffsetFor
                    val have     = live.currentTracks.mapTo(HashSet(live.currentTracks.size)) { it.uri }
                    val appended = if (playlist.id in dedupePagesFor || untrustedOffset)
                                       newTracks.filterNot { it.uri in have } else newTracks
                    val dupes    = newTracks.size - appended.size
                    val allTracks = live.currentTracks + appended
                    // Offset advances by the RAW page size (so client-filtered items stay counted —
                    // they are real API items) MINUS the overlap we just dropped (those are API items
                    // an earlier page already counted; counting them twice would skip that many
                    // tracks at the next page boundary).
                    // NOT for an untrusted offset: there the overlap means the offset was SHORT, not
                    // that the server's list shifted, and the page genuinely started where we asked —
                    // so `offset + raw` is the true next position. Subtracting there would leave the
                    // offset at (or below) where it started and re-fetch the same window on every
                    // scroll to the bottom, which is how a fully-overlapping page would loop.
                    val nextOffset = (live.playlistTracksOffset + rawPageSize - if (untrustedOffset) 0 else dupes)
                        .coerceAtLeast(allTracks.size)
                    // `resp.total` is the server talking, so it normally wins — except while a
                    // mutation reconcile for this playlist is still pending, when the page may be a
                    // pre-mutation read and its total would undo the optimistic count. That case is
                    // settled by applyServerTotal a moment later.
                    val reconcilePending = totalReconciles[playlist.id]?.isActive == true
                    val nextTotal = if (reconcilePending) maxOf(live.playlistTracksTotal, allTracks.size)
                                    else                  maxOf(resp.total, allTracks.size)
                    _uiState.update { it.copy(
                        currentTracks        = allTracks,
                        isLoadingMoreTracks  = false,
                        playlistTracksOffset = nextOffset,
                        playlistTracksTotal  = nextTotal,
                    ) }
                    untrustedOffsetFor -= playlist.id   // nextOffset is now anchored on a real page
                    playlist.snapshotId?.let { snap ->
                        // Persist the RAW boundary with the rows, so the next open resumes from the
                        // API position rather than re-deriving it from the filtered row count.
                        withContext(Dispatchers.IO) {
                            cache.saveTrackList(playlist.id, snap, allTracks, nextOffset)
                        }
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMoreTracks = false) }
                },
            )
        }
    }

    fun refreshCurrentTracks() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val s = _uiState.value
            if (s.currentPlaylist == null) {
                repository.getLikedSongs(limit = 50, offset = 0).fold(
                    onSuccess = { resp ->
                        val freshFirst50 = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                        _uiState.update { it.copy(
                            currentTracks    = freshFirst50,
                            // RAW page size, not filtered: see loadMoreLikedSongs.
                            likedSongsOffset = resp.items?.size ?: freshFirst50.size,
                            likedSongsTotal  = resp.total,
                            likedSongCount   = resp.total,
                        )}
                        withContext(Dispatchers.IO) {
                            // Preserve tracks cached beyond page 0 so background-fetch progress isn't lost.
                            // distinctBy heals any overlap between the fresh page 0 and the retained tail.
                            val beyond50 = cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.drop(50) ?: emptyList()
                            cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), (freshFirst50 + beyond50).distinctBy { it.id })
                        }
                    },
                    onFailure = { },
                )
            } else {
                val playlist = s.currentPlaylist
                repository.getPlaylistTracks(playlist.id).fold(
                    onSuccess = { resp ->
                        val tracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                        // selectionCleared() in the same emission: this replaces the list wholesale
                        // back to page 0, so a selection made past page 0 would survive with no
                        // checked row on screen while the pill still counted it — and the button it
                        // feeds is a DELETE. The gesture is gated out of selection mode in
                        // RightPaneContent, but the mode can still be entered from the song menu
                        // while a refresh is already in flight, so the clear is the real fix.
                        // (Liked Songs, the branch above, can never be in selection mode.)
                        _uiState.update { it.copy(
                            currentTracks        = tracks,
                            playlistTracksOffset = resp.items?.size ?: 0,   // reset paging to page 0
                            playlistTracksTotal  = resp.total,
                        ).selectionCleared() }
                        // Page 0 is fresh from the server, so the post-mutation stale-page window is
                        // over for this playlist — stop de-duping its pages (see dedupePagesFor).
                        dedupePagesFor -= playlist.id
                        untrustedOffsetFor -= playlist.id   // offset is page 0's own raw size again
                        // Same server total through the single authoritative writer, so a refresh
                        // heals the browser/left-pane card and the cached metadata too — not just
                        // the hero. Writes nothing when they already agree (the copies are identity),
                        // so the common case adds no emission.
                        applyServerTotal(playlist.id, resp.total)
                        if (playlist.snapshotId != null) {
                            // Page 0 again, so the raw boundary is this page's raw size — written in
                            // the same breath as the de-dupe clear above, which is only safe BECAUSE
                            // a trustworthy boundary lands with it.
                            val rawOffset = resp.items?.size ?: 0
                            withContext(Dispatchers.IO) {
                                cache.saveTrackList(playlist.id, playlist.snapshotId, tracks, rawOffset)
                            }
                        }
                        if (playlist.thumbnailUrl.isBlank()) {
                            withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, tracks) }
                            _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                        }
                    },
                    onFailure = { },
                )
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

}

/** Cap on "Jump back in" tiles — enough for two swipes of the row, cheap to build. */
private const val MAX_JUMP_BACK_IN = 10

/**
 * How long [LibraryViewModel.reconcilePlaylistTotal] waits before asking the server for a mutated
 * playlist's total. Long enough to coalesce a burst of edits and to clear Spotify's read-after-write
 * lag on the DELETE/POST that triggered it; short enough that the count settles while the user is
 * still looking at the playlist.
 */
private const val TOTAL_RECONCILE_DEBOUNCE_MS = 600L

/** Returns a copy with the metadata track count set to [total] (identity when already there). */
private fun SpotifyPlaylist.withTrackCount(total: Int): SpotifyPlaylist =
    if (trackCount == total) this
    else copy(tracksMeta = (tracksMeta ?: PlaylistTracksMeta(0, null)).copy(total = total))

/**
 * Returns the list with [playlistId]'s metadata track count set to [total] — the same shape
 * `LibraryCache` writes to disk, so the in-memory browser list and the cached one can't disagree.
 * Returns `this` untouched when the playlist isn't in the list or already reads [total], so it
 * never manufactures a state emission.
 */
private fun List<SpotifyPlaylist>.withTrackCount(playlistId: String, total: Int): List<SpotifyPlaylist> =
    if (none { it.id == playlistId && it.trackCount != total }) this
    else map { if (it.id == playlistId) it.withTrackCount(total) else it }

private fun Throwable?.isTransientNetworkError(): Boolean =
    this?.cause is java.net.UnknownHostException ||
    this?.cause is java.net.SocketException ||
    this?.message?.contains("Unable to resolve host") == true ||
    this?.message?.contains("Failed to connect") == true

class LibraryViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LibraryViewModel(container.spotifyRepository, container.libraryCache, container.mosaicGenerator, container.remoteManager, container.playerStateManager, container.settingsRepository) as T
}
