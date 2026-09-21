package com.crsmthw.lyra.ui.ipod

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LikedSongsIndexer
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.ipod.nav.IPodScreen
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState
import com.crsmthw.lyra.ui.ipod.nav.LCD_ROWS_SINGLE_LINE
import com.crsmthw.lyra.ui.ipod.nav.LCD_ROWS_TWO_LINE
import com.crsmthw.lyra.ui.ipod.nav.LcdIndexStatus
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.nav.LcdListState
import com.crsmthw.lyra.ui.ipod.nav.LcdNavDirection
import com.crsmthw.lyra.ui.ipod.nav.LcdNowPlaying
import com.crsmthw.lyra.ui.ipod.nav.LcdRepeat
import com.crsmthw.lyra.ui.ipod.nav.NowPlayingMode
import com.crsmthw.lyra.ui.ipod.wheel.ClickPitch
import com.crsmthw.lyra.ui.ipod.wheel.ClickSoundsConfig
import com.crsmthw.lyra.ui.ipod.wheel.WheelButton
import com.crsmthw.lyra.ui.ipod.wheel.WheelEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Milliseconds before a scrub gesture commits to a seek. */
private const val SCRUB_COMMIT_MS = 350L

/**
 * After the seek is sent, the scrubbed position stays on the bar this long so the player's
 * optimistic progress (set by PlayerStateManager.seekTo) has arrived before the bar reads it —
 * clearing at once let the bar flick back to the old position for a frame.
 */
private const val SCRUB_RELEASE_MS = 300L

/** A volume / shuffle / repeat bar left alone this long drops back to the scrubber, as the Classic's does. */
private const val MODE_IDLE_RESET_MS = 5_000L

/** Paging trigger: fetch more when the highlight is within this many rows of the end. */
private const val PAGE_TRIGGER_ROWS = 8

/**
 * The iPod's brain: owns the LCD back stack and each entry's list, turns [WheelEvent]s into
 * navigation (MENU pops, SELECT pushes/activates, Scroll moves the highlight — or scrubs on Now
 * Playing) and into [IPodEffect]s for anything that touches playback. Mirrors
 * `PlayerStateManager.state` into [IPodUiState.nowPlaying] and the iPod's own settings from
 * [SettingsRepository]. The transport buttons (PREVIOUS / NEXT / PLAY_PAUSE) are global: they
 * emit their effect whatever screen is showing.
 *
 * Reads the library cache-first (every read on Dispatchers.IO, once per screen entry — never per
 * frame), gates every network call on `playerStateManager.isRateLimited()` and calls
 * `noteRateLimited()` on a 429, per docs/SPOTIFY.md.
 *
 * ### Window model (Checkpoint B)
 *
 * The list state carries `firstVisibleIndex` + `visibleRows`. The LCD draws rows
 * `[firstVisibleIndex, firstVisibleIndex + visibleRows)` with NO scrolling of its own, so the
 * ViewModel owns the window:
 * - `visibleRows` = TWO_LINE if any item has a subtitle, else SINGLE_LINE.
 * - Scroll: newSel = clamp(sel + steps); then the Classic rule — if the selection goes past the
 *   bottom of the window, the window scrolls down; above the top, up; otherwise unchanged.
 * - A MENU pop restores the popped-to entry EXACTLY as the user left it (no window mutation).
 * - Paging appends only touch `items` and `hasMore`, never the window or selection.
 *
 * ### Recomposition
 *
 * The 1 Hz progress tick updates only [IPodUiState.nowPlaying]. The [stack] list instance and
 * every [IPodStackEntry] / [LcdListState] inside it are preserved across that update (the `copy`
 * touches only `nowPlaying`), so composables that read only the stack or its entries skip
 * automatically. Scrub-in-progress fields are carried forward from the previous `nowPlaying`
 * during the tick, so the scrub position is never stomped by the player mirror.
 *
 * ### Checkpoint C additions
 *
 * - **Cover Flow**: one row per liked song with [artUrl], pushed by [pushLikedList] (shared with
 *   Music). SELECT = the same [activateMusicItem] activation path as Music.
 * - **Prefetch**: [CoverArtPrefetcher] warms Coil's disk cache with covers near the Cover Flow
 *   highlight; constructed in [init] from the first cache read and updated on every liked-list
 *   load/reconcile/indexer append.
 * - **Indexer**: [LikedSongsIndexer.setFastDemand] held for the VM's lifetime; appended pages grow
 *   every Music/CoverFlow stack entry in place (not top-only).
 * - **Reconcile write**: Music/CoverFlow reconcile uses the atomic [LibraryCache.prependToLikedSongs]
 *   so an indexer append landing between the read and the write is not reverted.
 */
class IPodViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryCache: LibraryCache,
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
    /** Application-context AudioManager — the Now Playing volume bar drives Android's media stream. */
    private val audioManager: AudioManager?,
    private val likedSongsIndexer: LikedSongsIndexer,
    private val imageLoader: ImageLoader,
    private val appContext: Context,
) : ViewModel() {

    private val library = IPodLibrary(libraryCache, repository, playerStateManager)

    private val _uiState = MutableStateFlow(
        IPodUiState(
            stack = listOf(
                IPodStackEntry(
                    screen = IPodScreen.MainMenu,
                    title = LcdLabel.Res(R.string.ipod_menu_title),
                    list = LcdListState(items = buildMainMenu(hasNowPlaying = false)),
                ),
            ),
        ),
    )
    val uiState: StateFlow<IPodUiState> = _uiState.asStateFlow()

    private val _effects = Channel<IPodEffect>(Channel.BUFFERED)
    /** One-shot requests for IPodRoot to route to the player. */
    val effects: Flow<IPodEffect> = _effects.receiveAsFlow()

    /**
     * Tracks whether the main menu currently includes the "Now Playing" row. Rebuilt ONLY when
     * this flips (not on every 1 Hz tick), so the menu items list instance is stable across ticks.
     */
    private var lastHadNowPlaying = false

    /** The signed-in user's id, resolved once per iPod session for the Liked Songs collection URI. */
    private var cachedUserId: String? = null

    /**
     * Remembers the last track selection from a list so the Now Playing screen can show
     * "N of M" while that track is still playing.
     */
    private var lastSelection: TrackSelection? = null

    /** Scrub debounce job — cancelled and relaunched on every scrub step. */
    private var scrubJob: Job? = null

    /** Returns the Now Playing bar to the scrubber after [MODE_IDLE_RESET_MS] without interaction. */
    private var modeIdleJob: Job? = null

    /** The uri the mirror last emitted — a change cancels any pending scrub commit. */
    private var mirroredTrackUri: String? = null

    /** Guards against duplicate fetch launches for paged browse screens. */
    private val pagingInFlight = mutableSetOf<String>()

    /**
     * The server-computed next offset for each paged screen, keyed by a string that uniquely
     * identifies the screen instance. Written from `IPodLibrary`'s `nextOffset` on every
     * successful page; read by [maybeTriggerPaging]. Never derived from `items.size` — the
     * rendered list is filtered (nulls, isPlayable, de-dup) so its length != the API offset.
     */
    private val nextOffsetByKey = mutableMapOf<String, Int>()

    // ── Art prefetcher (Checkpoint C) ────────────────────────────────────────

    private val prefetcher = CoverArtPrefetcher(imageLoader, appContext, viewModelScope)

    /**
     * Maps a liked-row index to its prefetcher-url-list index, so [setFocus] passes the correct
     * collapsed index. The prefetcher collapses blanks and duplicates, while Cover Flow has one
     * row per song (duplicates of an album cover are expected). Built once per [setUrls] call.
     */
    private var rowToPrefetchIndex: IntArray = IntArray(0)

    /** Feed the prefetcher with the covers' urls and build the row→collapsed-index map. */
    private fun updatePrefetchUrls(rows: List<LcdItem>) {
        val rawUrls = rows.map { it.artUrl.orEmpty() }
        // Build the collapsed list and the row→collapsed-index map at the same time.
        val seen = LinkedHashMap<String, Int>()  // url → collapsed index
        val mapping = IntArray(rawUrls.size)
        for ((i, url) in rawUrls.withIndex()) {
            if (url.isBlank()) {
                mapping[i] = -1
            } else {
                val idx = seen.getOrPut(url) { seen.size }
                mapping[i] = idx
            }
        }
        rowToPrefetchIndex = mapping
        prefetcher.setUrls(seen.keys.toList())
    }

    /** Translate a row index to the collapsed prefetcher index and call setFocus. */
    private fun updatePrefetchFocus(rowIndex: Int) {
        val map = rowToPrefetchIndex
        if (rowIndex !in map.indices) return
        val collapsed = map[rowIndex]
        if (collapsed >= 0) prefetcher.setFocus(collapsed)
    }

    init {
        // Mirror all three clicker settings (enabled, volume, pitch) into the UI state.
        viewModelScope.launch {
            combine(
                settingsRepository.ipodClickSounds,
                settingsRepository.ipodClickVolume,
                settingsRepository.ipodClickPitch,
            ) { enabled, volume, pitchOrdinal ->
                ClickSoundsConfig(
                    enabled = enabled,
                    volumePercent = volume.coerceIn(0, 100),
                    pitch = ClickPitch.entries.getOrElse(pitchOrdinal.coerceIn(0, ClickPitch.entries.lastIndex)) { ClickPitch.MEDIUM },
                )
            }.collect { config ->
                _uiState.update { state ->
                    refreshSettingsRows(state.copy(clickSounds = config))
                }
            }
        }

        // Mirror the body colour (Settings → Color) into the UI state.
        viewModelScope.launch {
            settingsRepository.ipodBodyColor.collect { ordinal ->
                val color = IPodBodyColor.entries.getOrElse(ordinal) { IPodBodyColor.SILVER }
                _uiState.update { state -> refreshSettingsRows(state.copy(bodyColor = color)) }
            }
        }

        // Mirror the player state into nowPlaying, rebuilding the main menu only when
        // the presence of a playing track flips (not every second).
        viewModelScope.launch {
            playerStateManager.state
                .map { ps ->
                    val track = ps.currentTrack ?: return@map null
                    // "N of M": the current track's position in the list the user last picked from,
                    // so it follows skips and auto-advance. Done here so `distinctUntilChanged` on the
                    // whole LcdNowPlaying still skips duplicates. (indexOf over a few thousand uris
                    // once a second is nothing.)
                    val sel = lastSelection
                    val posInList = sel?.positionOf(track.uri)
                    val listSize = if (posInList != null) sel.sourceUris.size else null
                    LcdNowPlaying(
                        uri = track.uri,
                        title = track.name,
                        artist = track.allArtists,
                        album = track.album?.name ?: track.show?.name ?: "",
                        artUrl = track.artUrl,
                        isPlaying = ps.isPlaying,
                        progressMs = ps.progressMs,
                        durationMs = ps.durationMs,
                        positionInList = posInList,
                        listSize = listSize,
                        shuffleEnabled = ps.shuffleEnabled,
                        repeat = when (ps.repeatState) {
                            "context" -> LcdRepeat.ALL
                            "track" -> LcdRepeat.ONE
                            else -> LcdRepeat.OFF
                        },
                    )
                }
                .distinctUntilChanged()
                .collect { np ->
                    _uiState.update { state ->
                        // Carry a scrub-in-progress across the 1 Hz tick — of the SAME track only.
                        // A scrub belongs to one track; when the track changes (auto-advance, a
                        // transport button) the position must not leak onto the new one.
                        val enriched = if (np != null) {
                            val old = state.nowPlaying
                            val sameTrack = old != null && old.uri == np.uri
                            // The bar mode outlives the track; the volume is re-read while its bar shows
                            // so a hardware-key change reaches the LCD within a tick.
                            val mode = old?.mode ?: NowPlayingMode.SCRUB
                            np.copy(
                                scrubProgressMs = if (sameTrack) old.scrubProgressMs else null,
                                mode = mode,
                                volumePercent = if (mode == NowPlayingMode.VOLUME) readVolumePercent() else (old?.volumePercent ?: 0),
                            )
                        } else {
                            null
                        }

                        val hasNp = enriched != null
                        if (hasNp != lastHadNowPlaying) {
                            lastHadNowPlaying = hasNp
                            rebuildMainMenuIfOnTop(state.copy(nowPlaying = enriched), hasNp)
                        } else {
                            state.copy(nowPlaying = enriched)
                        }
                    }
                    // A pending scrub commit dies with the track it was scrubbing.
                    if (np?.uri != mirroredTrackUri) {
                        mirroredTrackUri = np?.uri
                        scrubJob?.cancel()
                        scrubJob = null
                    }
                }
        }

        // ── Checkpoint C: indexer + prefetch bootstrap ──────────────────────────

        // Activate the fast-cadence demand for the whole iPod session.
        likedSongsIndexer.setFastDemand(true)

        // Seed the prefetcher from the cache on first entry (before the appended collector is live,
        // so a page emitted in the gap between init and the collector's registration is accepted as a
        // degradation — a re-entry via MENU-out/in re-reads the cache and catches up).
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)
            }
            val rows = cached?.tracks.orEmpty().distinctBy { it.uri }.map(::likedRow)
            if (rows.isNotEmpty()) {
                updatePrefetchUrls(rows)
                prefetcher.setFocus(0)
            }
        }

        // Collect indexer state → likedIndex (non-null only while incomplete and known).
        viewModelScope.launch {
            likedSongsIndexer.state
                .map { s ->
                    if (s.total != null && s.cached != null && !s.complete && s.cached < s.total) {
                        LcdIndexStatus(s.cached, s.total)
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collect { status ->
                    _uiState.update { state ->
                        if (state.likedIndex == status) state
                        else state.copy(likedIndex = status)
                    }
                }
        }

        // Collect indexer appended pages → grow every Music/CoverFlow entry in the stack.
        viewModelScope.launch {
            likedSongsIndexer.appended.collect { newTracks ->
                if (newTracks.isEmpty()) return@collect
                val newRows = newTracks.map(::likedRow)
                // Capture the grown liked items for a post-update prefetch call — never call
                // updatePrefetchUrls inside the CAS lambda (the lambda can re-run under contention
                // with the 1 Hz mirror, and it must not have side effects).
                var grownItems: List<LcdItem>? = null
                _uiState.update { state ->
                    grownItems = null   // reset per CAS attempt
                    var changed = false
                    val updatedStack = state.stack.map { entry ->
                        val isLikedScreen = entry.screen is IPodScreen.Music ||
                            entry.screen is IPodScreen.CoverFlow
                        if (!isLikedScreen) return@map entry
                        // Skip entries still loading (no items yet) — their cache read will
                        // include these rows when it lands.
                        if (entry.list.items.isEmpty()) return@map entry
                        val existingIds = entry.list.items.mapTo(HashSet()) { it.id }
                        val toAdd = newRows.filter { it.id !in existingIds }
                        if (toAdd.isEmpty()) return@map entry
                        changed = true
                        val combined = entry.list.items + toAdd
                        val visibleRows = computeVisibleRows(combined)
                        // Never move selectedIndex or firstVisibleIndex — paging appends only grow items.
                        val updated = entry.copy(
                            list = entry.list.copy(
                                items = combined,
                                visibleRows = visibleRows,
                            ),
                        )
                        // Capture the last liked-screen items for the prefetch update.
                        grownItems = combined
                        updated
                    }
                    if (!changed) return@update state
                    state.copy(stack = updatedStack)
                }
                // Side effects AFTER the update.
                grownItems?.let { items ->
                    updatePrefetchUrls(items)
                    // Re-focus: the items grew but selectedIndex did not move; use the current top
                    // only if it is Cover Flow (Music ignores focus).
                    val top = _uiState.value.current
                    if (top.screen is IPodScreen.CoverFlow) {
                        updatePrefetchFocus(top.list.selectedIndex)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        likedSongsIndexer.setFastDemand(false)
        prefetcher.cancel()
        super.onCleared()
    }

    // ── Wheel event dispatch ──────────────────────────────────────────────────

    /**
     * Returns whether the event moved something the user can see — the highlight or the scrub
     * position. The wheel fires its detent click and haptic only on `true`, so the ends of a list
     * (and of a track) are silent, as on the Classic. Button presses always count.
     */
    fun onWheelEvent(event: WheelEvent): Boolean = when (event) {
        is WheelEvent.Scroll -> handleScroll(event.steps)
        is WheelEvent.Press -> { handlePress(event.button); true }
    }

    /** @return true when the highlight or the scrub position actually changed. */
    private fun handleScroll(steps: Int): Boolean {
        var shouldPage = false
        var moved = false
        var pendingEffect: IPodEffect? = null
        _uiState.update { state ->
            moved = false   // reset per attempt: the CAS lambda can re-run under contention
            pendingEffect = null
            val top = state.current
            if (top.screen is IPodScreen.NowPlaying) {
                // The wheel drives whatever bar is showing: position, media volume, shuffle, repeat.
                val np = state.nowPlaying ?: return@update state
                val next = when (np.mode) {
                    NowPlayingMode.SCRUB -> computeScrub(state, steps)
                    NowPlayingMode.VOLUME -> computeVolume(state, steps)
                    NowPlayingMode.SHUFFLE -> {
                        val target = steps > 0   // clockwise = on, counter-clockwise = off
                        if (target == np.shuffleEnabled) {
                            state
                        } else {
                            pendingEffect = IPodEffect.SetShuffle(target)
                            state.copy(nowPlaying = np.copy(shuffleEnabled = target))
                        }
                    }
                    NowPlayingMode.REPEAT -> {
                        // Off → All → One, clockwise; back again counter-clockwise; silent at the ends.
                        val idx = (np.repeat.ordinal + (if (steps > 0) 1 else -1)).coerceIn(0, LcdRepeat.entries.lastIndex)
                        val target = LcdRepeat.entries[idx]
                        if (target == np.repeat) {
                            state
                        } else {
                            pendingEffect = IPodEffect.SetRepeat(target.spotifyState())
                            state.copy(nowPlaying = np.copy(repeat = target))
                        }
                    }
                }
                moved = next !== state
                return@update next
            }
            val items = top.list.items
            if (items.isEmpty()) return@update state
            val visibleRows = top.list.visibleRows
            val newIndex = (top.list.selectedIndex + steps).coerceIn(0, items.lastIndex)
            if (newIndex == top.list.selectedIndex) return@update state
            moved = true

            // Classic window rule: selection drives the window, window never moves independently.
            var first = top.list.firstVisibleIndex
            if (newIndex >= first + visibleRows) {
                first = newIndex - visibleRows + 1
            } else if (newIndex < first) {
                first = newIndex
            }
            first = first.coerceIn(0, maxOf(0, items.size - visibleRows))

            val updatedEntry = top.copy(
                list = top.list.copy(selectedIndex = newIndex, firstVisibleIndex = first),
            )
            val newState = state.copy(stack = state.stack.dropLast(1) + updatedEntry)

            // Decide whether to page — the actual launch happens after update returns.
            shouldPage = shouldTriggerPaging(newState)

            newState
        }

        // Side effects AFTER the update — never inside the CAS lambda.
        if (shouldPage) triggerPaging()
        pendingEffect?.let { effect -> viewModelScope.launch { _effects.send(effect) } }

        // The scrub debounce belongs to the Now Playing screen only — a detent on any list
        // must never re-arm (or keep deferring) a seek.
        val currentTop = _uiState.value.current
        if (currentTop.screen is IPodScreen.NowPlaying) {
            launchScrubCommitIfNeeded()
            armModeIdleReset()   // a turn on the volume / shuffle / repeat bar keeps it up
        }

        // Cover Flow: update prefetch focus to the new highlight after the state update.
        if (currentTop.screen is IPodScreen.CoverFlow && moved) {
            updatePrefetchFocus(currentTop.list.selectedIndex)
        }

        return moved
    }

    private fun LcdRepeat.spotifyState(): String = when (this) {
        LcdRepeat.OFF -> "off"
        LcdRepeat.ALL -> "context"
        LcdRepeat.ONE -> "track"
    }

    /** Android media volume as 0..100, or 0 without an AudioManager. */
    private fun readVolumePercent(): Int {
        val am = audioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /**
     * One wheel detent on the volume bar = one Android media-volume step. Returns the same state at
     * the ends so the wheel stays silent there. (The set is idempotent, so a CAS re-run is harmless.)
     */
    private fun computeVolume(state: IPodUiState, steps: Int): IPodUiState {
        val am = audioManager ?: return state
        val np = state.nowPlaying ?: return state
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return state
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + steps).coerceIn(0, max)
        if (target == current) return state
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return state.copy(nowPlaying = np.copy(volumePercent = target * 100 / max))
    }

    /** SELECT on Now Playing: scrubber → volume → shuffle → repeat → scrubber. */
    private fun cycleNowPlayingMode() {
        commitPendingScrubNow()
        _uiState.update { state ->
            val np = state.nowPlaying ?: return@update state
            val next = NowPlayingMode.entries[(np.mode.ordinal + 1) % NowPlayingMode.entries.size]
            state.copy(
                nowPlaying = np.copy(
                    mode = next,
                    volumePercent = if (next == NowPlayingMode.VOLUME) readVolumePercent() else np.volumePercent,
                ),
            )
        }
        armModeIdleReset()
    }

    /**
     * (Re)start the idle timer while a non-scrubber bar shows; on expiry the bar slides back to the
     * scrubber. Cancelled when the scrubber is already showing or Now Playing is left.
     */
    private fun armModeIdleReset() {
        modeIdleJob?.cancel()
        val mode = _uiState.value.nowPlaying?.mode ?: return
        if (mode == NowPlayingMode.SCRUB) return
        modeIdleJob = viewModelScope.launch {
            delay(MODE_IDLE_RESET_MS)
            _uiState.update { state ->
                val np = state.nowPlaying ?: return@update state
                if (np.mode == NowPlayingMode.SCRUB) state else state.copy(nowPlaying = np.copy(mode = NowPlayingMode.SCRUB))
            }
        }
    }

    /**
     * Compute the new state for a scrub step on Now Playing. Pure — no side effects.
     * The actual commit job is launched AFTER `_uiState.update` returns, via
     * [launchScrubCommitIfNeeded].
     */
    private fun computeScrub(state: IPodUiState, steps: Int): IPodUiState {
        val np = state.nowPlaying ?: return state
        val duration = np.durationMs
        if (duration <= 0) return state
        val step = maxOf(1_000L, duration / 100)
        val base = np.scrubProgressMs ?: np.progressMs
        val newScrub = (base + steps * step).coerceIn(0, duration)
        if (newScrub == base) return state   // already at 0:00 or the end — nothing moved, no click
        return state.copy(nowPlaying = np.copy(scrubProgressMs = newScrub))
    }

    /**
     * If a scrub is in progress (scrubProgressMs != null), cancel the old debounce and start
     * a new one. Called outside `_uiState.update` to avoid side effects in the CAS lambda.
     */
    private fun launchScrubCommitIfNeeded() {
        val np = _uiState.value.nowPlaying ?: return
        if (np.scrubProgressMs == null) return
        val trackUri = np.uri
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(SCRUB_COMMIT_MS)
            commitScrub(trackUri)
        }
    }

    /** Commit a pending scrub NOW (MENU or Play/Pause pressed while the debounce was running). */
    private fun commitPendingScrubNow() {
        val np = _uiState.value.nowPlaying ?: return
        if (np.scrubProgressMs == null) return
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch { commitScrub(np.uri) }
    }

    /**
     * Seek to the scrubbed position. Value and duration come from ONE snapshot, and only if the
     * track is still the one that was scrubbed — otherwise the scrub is dropped, never applied to
     * whatever started playing since.
     */
    private suspend fun commitScrub(trackUri: String) {
        val np = _uiState.value.nowPlaying
        val scrubMs = np?.scrubProgressMs
        if (np == null || scrubMs == null || np.uri != trackUri || np.durationMs <= 0) {
            clearScrub()
            return
        }
        _effects.send(IPodEffect.SeekTo(scrubMs.toFloat() / np.durationMs.toFloat()))
        delay(SCRUB_RELEASE_MS)
        _uiState.update { s ->
            val cur = s.nowPlaying ?: return@update s
            // A newer scrub owns the field now — leave it to its own commit.
            if (cur.scrubProgressMs != scrubMs) return@update s
            s.copy(nowPlaying = cur.copy(scrubProgressMs = null))
        }
    }

    /** Drop any pending scrub without seeking (the track is about to change). */
    private fun clearScrub() {
        scrubJob?.cancel()
        scrubJob = null
        _uiState.update { s ->
            val cur = s.nowPlaying ?: return@update s
            if (cur.scrubProgressMs == null) s else s.copy(nowPlaying = cur.copy(scrubProgressMs = null))
        }
    }

    private fun handlePress(button: WheelButton) {
        when (button) {
            WheelButton.MENU -> handleMenu()
            WheelButton.SELECT -> handleSelect()
            WheelButton.PLAY_PAUSE -> {
                commitPendingScrubNow()
                viewModelScope.launch { _effects.send(IPodEffect.PlayPause) }
            }
            WheelButton.NEXT -> {
                clearScrub()
                viewModelScope.launch { _effects.send(IPodEffect.Next) }
            }
            WheelButton.PREVIOUS -> {
                clearScrub()
                viewModelScope.launch { _effects.send(IPodEffect.Previous) }
            }
        }
    }

    private fun handleMenu() {
        val leavingNowPlaying = _uiState.value.let {
            it.stack.size > 1 && it.current.screen is IPodScreen.NowPlaying
        }
        _uiState.update { state ->
            if (state.stack.size <= 1) return@update state // root — do nothing
            // Pop without mutating the entry we return to — preserves its window exactly.
            state.copy(
                stack = state.stack.dropLast(1),
                direction = LcdNavDirection.BACK,
                // Leaving Now Playing puts the bar back to the scrubber (the Classic does the same
                // after a moment), so the next visit starts where the wheel is expected to scrub.
                nowPlaying = if (state.current.screen is IPodScreen.NowPlaying) {
                    state.nowPlaying?.copy(mode = NowPlayingMode.SCRUB)
                } else {
                    state.nowPlaying
                },
            )
        }
        // A scrub left half-done on the way out is applied, not forgotten — and it can never
        // be re-armed from a list, so this is its last chance.
        if (leavingNowPlaying) {
            commitPendingScrubNow()
            modeIdleJob?.cancel()
            modeIdleJob = null
        }
    }

    private fun handleSelect() {
        val state = _uiState.value
        val top = state.current
        if (top.screen is IPodScreen.NowPlaying) {
            cycleNowPlayingMode()
            return
        }
        val items = top.list.items
        if (items.isEmpty()) return
        val selected = items.getOrNull(top.list.selectedIndex) ?: return

        when (top.screen) {
            is IPodScreen.MainMenu -> activateMainMenuItem(selected.id)
            is IPodScreen.Music -> activateMusicItem(selected, top.list.selectedIndex, items)
            is IPodScreen.CoverFlow -> activateMusicItem(selected, top.list.selectedIndex, items)
            is IPodScreen.Settings -> activateSettingsItem(selected.id)
            is IPodScreen.Albums -> activateAlbumItem(selected)
            is IPodScreen.AlbumTracks -> activateAlbumTrackItem(top.screen, selected, top.list.selectedIndex, items)
            is IPodScreen.Artists -> activateArtistItem(selected)
            is IPodScreen.ArtistAlbums -> activateArtistAlbumItem(selected)
            is IPodScreen.Playlists -> activatePlaylistItem(selected)
            is IPodScreen.PlaylistTracks -> activatePlaylistTrackItem(top.screen, selected, top.list.selectedIndex, items)
            is IPodScreen.Podcasts -> activateShowItem(selected)
            is IPodScreen.ShowEpisodes -> activateEpisodeItem(top.screen, selected, top.list, items)
            else -> {}
        }
    }

    // ── Main menu activation ──────────────────────────────────────────────────

    private fun activateMainMenuItem(id: String) {
        when (id) {
            "coverflow" -> pushCoverFlow()
            "music" -> pushMusic()
            "albums" -> pushAlbums()
            "artists" -> pushArtists()
            "playlists" -> pushPlaylists()
            "podcasts" -> pushPodcasts()
            "shuffle" -> handleShuffleSongs()
            "nowplaying" -> push(
                IPodScreen.NowPlaying,
                LcdLabel.Res(R.string.ipod_menu_now_playing),
            )
            "settings" -> pushSettings()
        }
    }

    // ── Music / Cover Flow (liked songs from cache) ─────────────────────────

    /**
     * Shared loader for Music and Cover Flow. Both show the liked songs list — Cover Flow adds
     * [artUrl] per row. Push, load from the cache, replace the top, then reconcile the server.
     */
    private fun pushLikedList(screen: IPodScreen, title: LcdLabel) {
        push(screen = screen, title = title, loading = true)
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)
            }
            val rows = cached?.tracks.orEmpty().distinctBy { it.uri }.map(::likedRow)
            replaceTopItems(screen, rows)

            // Update prefetch after the initial load — both Music and CoverFlow feed the same
            // liked-song urls so a reconcile's newly-prepended songs reach the prefetcher even
            // while the user is in Music. Focus is Cover-Flow-only (Music ignores it).
            updatePrefetchUrls(rows)
            if (screen is IPodScreen.CoverFlow) {
                updatePrefetchFocus(0)
            }

            // Reconcile: use the atomic prependToLikedSongs so the indexer's concurrent appends
            // are not reverted.
            val merged = library.reconcileLikedSongs(cached) ?: return@launch
            val reconciled = merged.distinctBy { it.uri }.map(::likedRow)
            retopItemsPreservingHighlight(screen, reconciled)

            updatePrefetchUrls(reconciled)
            // Re-focus after reconcile: retopItemsPreservingHighlight may have shifted the
            // highlight index when songs were prepended.
            if (screen is IPodScreen.CoverFlow) {
                val top = _uiState.value.current
                if (top.screen is IPodScreen.CoverFlow) {
                    updatePrefetchFocus(top.list.selectedIndex)
                }
            }
        }
    }

    private fun pushMusic() {
        pushLikedList(IPodScreen.Music, LcdLabel.Res(R.string.ipod_menu_music))
    }

    private fun pushCoverFlow() {
        pushLikedList(IPodScreen.CoverFlow, LcdLabel.Res(R.string.ipod_menu_cover_flow))
    }

    private fun likedRow(track: SpotifyTrack) = LcdItem(
        id = track.uri,
        title = LcdLabel.Text(track.name),
        subtitle = LcdLabel.Text(track.allArtists),
        artUrl = track.artUrl.takeIf { it.isNotBlank() },
    )

    private fun activateMusicItem(item: LcdItem, index: Int, items: List<LcdItem>) {
        rememberSelection(item.id, index, items.map { it.id })
        viewModelScope.launch {
            _effects.send(IPodEffect.PlayLikedSong(item.id, shuffle = false))
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Albums ────────────────────────────────────────────────────────────────

    private fun pushAlbums() {
        push(
            screen = IPodScreen.Albums,
            title = LcdLabel.Res(R.string.ipod_menu_albums),
            loading = true,
        )
        viewModelScope.launch {
            library.savedAlbums().fold(
                onSuccess = { result ->
                    val items = result.albums.mapNotNull { album ->
                        // Gson allocates via Unsafe: a "non-null" id / name can still arrive null.
                        @Suppress("SENSELESS_COMPARISON")
                        val usable = album.id != null && album.name != null
                        if (!usable) return@mapNotNull null
                        LcdItem(
                            id = album.id,
                            title = LcdLabel.Text(album.name),
                            subtitle = LcdLabel.Text(
                                album.artists?.joinToString(" · ") { it.name } ?: "",
                            ),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Albums, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Albums, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateAlbumItem(item: LcdItem) {
        val albumId = item.id
        val albumUri = "spotify:album:$albumId"
        push(
            screen = IPodScreen.AlbumTracks(albumId, albumUri),
            title = item.title,
            loading = true,
        )
        viewModelScope.launch {
            library.albumTracks(albumId).fold(
                onSuccess = { result ->
                    val items = result.tracks.map { track ->
                        LcdItem(
                            id = track.uri,
                            title = LcdLabel.Text(track.name),
                            subtitle = LcdLabel.Text(track.allArtists),
                        )
                    }
                    replaceTopItems(IPodScreen.AlbumTracks(albumId, albumUri), items)
                },
                onFailure = {
                    setTopError(
                        IPodScreen.AlbumTracks(albumId, albumUri),
                        LcdLabel.Res(R.string.ipod_vm_load_error),
                    )
                },
            )
        }
    }

    private fun activateAlbumTrackItem(
        screen: IPodScreen.AlbumTracks,
        item: LcdItem,
        index: Int,
        items: List<LcdItem>,
    ) {
        rememberSelection(item.id, index, items.map { it.id })
        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = item.id,
                    contextUri = screen.albumUri,
                    // No index: it would be this FILTERED list's position, and the App Remote
                    // fallback would skipToIndex into the real context with it. The Web API path
                    // positions by uri; the fallback plays the uri itself.
                    index = null,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Artists ───────────────────────────────────────────────────────────────

    private fun pushArtists() {
        push(
            screen = IPodScreen.Artists,
            title = LcdLabel.Res(R.string.ipod_menu_artists),
            loading = true,
        )
        viewModelScope.launch {
            library.followedArtists().fold(
                onSuccess = { result ->
                    val items = result.artists.mapNotNull { artist ->
                        @Suppress("SENSELESS_COMPARISON")
                        val usable = artist.id != null && artist.name != null
                        if (!usable) return@mapNotNull null
                        LcdItem(
                            id = artist.id,
                            title = LcdLabel.Text(artist.name),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Artists, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Artists, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateArtistItem(item: LcdItem) {
        val artistId = item.id
        push(
            screen = IPodScreen.ArtistAlbums(artistId),
            title = item.title,
            loading = true,
        )
        loadArtistAlbums(artistId, offset = 0)
    }

    private fun loadArtistAlbums(artistId: String, offset: Int) {
        val key = "artist_albums_$artistId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.artistAlbums(artistId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    val newItems = result.albums.map { album ->
                        val parts = listOfNotNull(
                            album.releaseYear.takeIf { it.isNotBlank() }?.let { LcdLabel.Text(it) },
                            album.albumType?.takeIf { it.isNotBlank() }
                                ?.let { LcdLabel.Text(it.replaceFirstChar { c -> c.uppercaseChar() }) },
                        )
                        LcdItem(
                            id = album.id,
                            title = LcdLabel.Text(album.name),
                            subtitle = if (parts.isEmpty()) null else LcdLabel.Joined(parts),
                            hasSubmenu = true,
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.ArtistAlbums(artistId),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.ArtistAlbums(artistId),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    private fun activateArtistAlbumItem(item: LcdItem) {
        // Push into the album's tracks.
        activateAlbumItem(item)
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    private fun pushPlaylists() {
        push(
            screen = IPodScreen.Playlists,
            title = LcdLabel.Res(R.string.ipod_menu_playlists),
            loading = true,
        )
        viewModelScope.launch {
            val userId = cachedUserId ?: library.resolveUserId().also { cachedUserId = it }
            if (userId == null) {
                setTopError(IPodScreen.Playlists, LcdLabel.Res(R.string.ipod_vm_load_error))
                return@launch
            }
            library.ownedPlaylists(userId).fold(
                onSuccess = { result ->
                    val items = result.playlists.map { playlist ->
                        LcdItem(
                            id = playlist.id,
                            title = LcdLabel.Text(playlist.name),
                            subtitle = LcdLabel.Plural(R.plurals.library_track_count, playlist.trackCount),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Playlists, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Playlists, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activatePlaylistItem(item: LcdItem) {
        val playlistId = item.id
        val playlistUri = "spotify:playlist:$playlistId"
        push(
            screen = IPodScreen.PlaylistTracks(playlistId, playlistUri),
            title = item.title,
            loading = true,
        )
        loadPlaylistTracks(playlistId, playlistUri, offset = 0)
    }

    private fun loadPlaylistTracks(playlistId: String, playlistUri: String, offset: Int) {
        val key = "playlist_tracks_$playlistId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.playlistTracks(playlistId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    // Row ids are "uri#occurrence" (the project's playlist-row convention): a
                    // playlist may hold the same track twice, and a bare-uri id would let the
                    // page-boundary de-dupe drop the second copy.
                    val occurrences = HashMap<String, Int>()
                    if (offset != 0) {
                        val top = _uiState.value.current
                        if (top.screen == IPodScreen.PlaylistTracks(playlistId, playlistUri)) {
                            for (row in top.list.items) {
                                val u = row.id.substringBefore('#')
                                occurrences[u] = (occurrences[u] ?: 0) + 1
                            }
                        }
                    }
                    val newItems = result.tracks.map { track ->
                        val n = occurrences[track.uri] ?: 0
                        occurrences[track.uri] = n + 1
                        LcdItem(
                            id = "${track.uri}#$n",
                            title = LcdLabel.Text(track.name),
                            subtitle = LcdLabel.Text(track.allArtists),
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.PlaylistTracks(playlistId, playlistUri),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                    // A cached first page is shown at once, then checked against a fresh one.
                    if (offset == 0 && result.fromCache) {
                        reconcilePlaylistRows(playlistId, playlistUri, result.tracks.map { it.uri }, key)
                    }
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.PlaylistTracks(playlistId, playlistUri),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    private fun activatePlaylistTrackItem(
        screen: IPodScreen.PlaylistTracks,
        item: LcdItem,
        index: Int,
        items: List<LcdItem>,
    ) {
        val trackUri = item.id.substringBefore('#')   // rows are "uri#occurrence"
        rememberSelection(trackUri, index, items.map { it.id.substringBefore('#') })
        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = trackUri,
                    contextUri = screen.playlistUri,
                    // No index — see activateAlbumTrackItem: a filtered-list position must never
                    // reach skipToIndex (playlists also hide episodes and unplayable tracks).
                    index = null,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Podcasts ──────────────────────────────────────────────────────────────

    private fun pushPodcasts() {
        push(
            screen = IPodScreen.Podcasts,
            title = LcdLabel.Res(R.string.ipod_menu_podcasts),
            loading = true,
        )
        viewModelScope.launch {
            library.followedShows().fold(
                onSuccess = { result ->
                    val items = result.shows.mapNotNull { show ->
                        val id = show.id ?: return@mapNotNull null
                        val name = show.name ?: return@mapNotNull null
                        val episodeCount = show.totalEpisodes
                        val subtitle = episodeCount?.takeIf { it > 0 }
                            ?.let { LcdLabel.Plural(R.plurals.show_episode_count, it) }
                        LcdItem(
                            id = id,
                            title = LcdLabel.Text(name),
                            subtitle = subtitle,
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Podcasts, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Podcasts, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateShowItem(item: LcdItem) {
        val showId = item.id
        push(
            screen = IPodScreen.ShowEpisodes(showId),
            title = item.title,
            loading = true,
        )
        loadShowEpisodes(showId, offset = 0)
    }

    private fun loadShowEpisodes(showId: String, offset: Int) {
        val key = "show_episodes_$showId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.showEpisodes(showId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    val newItems = result.episodes.map { ep ->
                        LcdItem(
                            id = ep.id,
                            title = LcdLabel.Text(ep.name),
                            subtitle = ep.subtitle,
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.ShowEpisodes(showId),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                    // Stash the episode data for playback.
                    stashEpisodeData(showId, result.episodes, offset == 0)
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.ShowEpisodes(showId),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    /** Episode data stashed for queue building and resume point access. */
    private val episodeDataByShow = mutableMapOf<String, MutableList<IPodLibrary.EpisodeItem>>()

    private fun stashEpisodeData(showId: String, episodes: List<IPodLibrary.EpisodeItem>, isFirstPage: Boolean) {
        if (isFirstPage) {
            episodeDataByShow[showId] = episodes.toMutableList()
        } else {
            val existing = episodeDataByShow.getOrPut(showId) { mutableListOf() }
            val existingIds = existing.map { it.id }.toSet()
            existing += episodes.filter { it.id !in existingIds }
        }
    }

    private fun activateEpisodeItem(
        screen: IPodScreen.ShowEpisodes,
        item: LcdItem,
        listState: LcdListState,
        items: List<LcdItem>,
    ) {
        val episodeId = item.id
        val episodes = episodeDataByShow[screen.showId] ?: emptyList()
        val episode = episodes.find { it.id == episodeId }
        val uri = episode?.uri ?: "spotify:episode:$episodeId"

        rememberSelection(uri, listState.selectedIndex, items.map { "spotify:episode:${it.id}" })

        val queue = library.buildEpisodeQueue(episodes, episodeId)

        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = uri,
                    uris = queue,
                    startPositionMs = episode?.resumePositionMs,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Shuffle Songs ─────────────────────────────────────────────────────────

    private fun handleShuffleSongs() {
        viewModelScope.launch {
            val userId = cachedUserId ?: library.resolveUserId().also { cachedUserId = it }
            if (userId != null) {
                _effects.send(IPodEffect.ShuffleContext("spotify:user:$userId:collection"))
            }
            // The wheel has already clicked, so the LCD must move either way: with no user id
            // Now Playing shows its empty state rather than the menu sitting inert.
            if (_uiState.value.current.screen !is IPodScreen.NowPlaying) {
                push(
                    IPodScreen.NowPlaying,
                    LcdLabel.Res(R.string.ipod_menu_now_playing),
                )
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun pushSettings() {
        val config = _uiState.value.clickSounds
        push(
            screen = IPodScreen.Settings,
            title = LcdLabel.Res(R.string.ipod_menu_settings),
            items = buildSettingsItems(config, _uiState.value.bodyColor),
        )
    }

    /** If the Settings screen is on top, rebuild its rows from [state]'s current values. */
    private fun refreshSettingsRows(state: IPodUiState): IPodUiState {
        val top = state.stack.lastOrNull() ?: return state
        if (top.screen !is IPodScreen.Settings) return state
        val updatedEntry = top.copy(
            list = top.list.copy(items = buildSettingsItems(state.clickSounds, state.bodyColor)),
        )
        return state.copy(stack = state.stack.dropLast(1) + updatedEntry)
    }

    private fun activateSettingsItem(id: String) {
        when (id) {
            "clicksounds" -> {
                val current = _uiState.value.clickSounds.enabled
                viewModelScope.launch { settingsRepository.setIpodClickSounds(!current) }
            }
            "clickvolume" -> {
                val current = _uiState.value.clickSounds.volumePercent
                val next = when {
                    current < 50 -> 50
                    current < 75 -> 75
                    current < 100 -> 100
                    else -> 25
                }
                viewModelScope.launch { settingsRepository.setIpodClickVolume(next) }
            }
            "clickpitch" -> {
                val current = _uiState.value.clickSounds.pitch
                val nextOrdinal = (current.ordinal + 1) % ClickPitch.entries.size
                viewModelScope.launch { settingsRepository.setIpodClickPitch(nextOrdinal) }
            }
            "bodycolor" -> {
                val current = _uiState.value.bodyColor
                val nextOrdinal = (current.ordinal + 1) % IPodBodyColor.entries.size
                viewModelScope.launch { settingsRepository.setIpodBodyColor(nextOrdinal) }
            }
            "ipodmode" -> {
                viewModelScope.launch { settingsRepository.setIpodEnabled(false) }
            }
        }
    }

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Pure check: returns true if the top screen should fetch more. Called inside update. */
    private fun shouldTriggerPaging(state: IPodUiState): Boolean {
        val top = state.current
        if (!top.list.hasMore) return false
        val remaining = top.list.items.size - top.list.selectedIndex - 1
        return remaining <= PAGE_TRIGGER_ROWS
    }

    /** Launch the actual paging fetch. Called OUTSIDE `_uiState.update`. */
    private fun triggerPaging() {
        val top = _uiState.value.current
        when (val screen = top.screen) {
            is IPodScreen.ArtistAlbums -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadArtistAlbums(screen.artistId, offset)
            }
            is IPodScreen.PlaylistTracks -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadPlaylistTracks(screen.playlistId, screen.playlistUri, offset)
            }
            is IPodScreen.ShowEpisodes -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadShowEpisodes(screen.showId, offset)
            }
            else -> {}
        }
    }

    /** Unique key for paging state tracking. */
    private fun pagingKey(screen: IPodScreen): String = when (screen) {
        is IPodScreen.ArtistAlbums -> "artist_albums_${screen.artistId}"
        is IPodScreen.PlaylistTracks -> "playlist_tracks_${screen.playlistId}"
        is IPodScreen.ShowEpisodes -> "show_episodes_${screen.showId}"
        else -> ""
    }

    // ── Track selection memory ────────────────────────────────────────────────

    /**
     * The list a song was picked from. "N of M" is the CURRENT track's position in that list — so it
     * survives a skip or an auto-advance (the Classic shows the position in the playing context) and
     * only disappears when something outside the list starts playing.
     */
    private data class TrackSelection(
        val uri: String,
        /** Every row's uri in the list the selection came from, in order. */
        val sourceUris: List<String>,
    ) {
        /** 1-based position of [trackUri] in the source list, or null if it is not in it. */
        fun positionOf(trackUri: String): Int? =
            sourceUris.indexOf(trackUri).takeIf { it >= 0 }?.plus(1)
    }

    private fun rememberSelection(uri: String, index: Int, sourceUris: List<String>) {
        lastSelection = TrackSelection(uri = uri, sourceUris = sourceUris)
    }

    // ── Stack helpers ─────────────────────────────────────────────────────────

    /**
     * Push a new screen onto the stack. Guards against pushing the same screen twice from a
     * double-SELECT (the Classic ignores it).
     */
    private fun push(
        screen: IPodScreen,
        title: LcdLabel,
        items: List<LcdItem> = emptyList(),
        loading: Boolean = false,
    ) {
        _uiState.update { state ->
            if (state.current.screen == screen) return@update state
            val visibleRows = computeVisibleRows(items)
            val entry = IPodStackEntry(
                screen = screen,
                title = title,
                list = LcdListState(
                    items = items,
                    isLoading = loading,
                    visibleRows = visibleRows,
                ),
            )
            state.copy(
                stack = state.stack + entry,
                direction = LcdNavDirection.FORWARD,
            )
        }
    }

    /**
     * Replace the items on the top entry, setting the appropriate `visibleRows` and clamping
     * the window. Used when a screen's data arrives asynchronously.
     */
    private fun replaceTopItems(
        expectedScreen: IPodScreen,
        items: List<LcdItem>,
        hasMore: Boolean = false,
    ) {
        updateTopEntry(expectedScreen) { entry ->
            val visibleRows = computeVisibleRows(items)
            val sel = entry.list.selectedIndex.coerceIn(0, maxOf(0, items.lastIndex))
            val first = entry.list.firstVisibleIndex.coerceIn(0, maxOf(0, items.size - visibleRows))
            entry.copy(
                list = entry.list.copy(
                    items = items,
                    isLoading = false,
                    error = null,
                    visibleRows = visibleRows,
                    selectedIndex = sel,
                    firstVisibleIndex = first,
                    hasMore = hasMore,
                ),
            )
        }
    }

    /**
     * Append new items to the top entry (paging) or replace if this is the first page.
     * Never moves the selection or window — only items and hasMore change.
     */
    private fun appendOrReplaceTopItems(
        expectedScreen: IPodScreen,
        newItems: List<LcdItem>,
        hasMore: Boolean,
        isFirstPage: Boolean,
    ) {
        updateTopEntry(expectedScreen) { entry ->
            val combined = if (isFirstPage) {
                newItems
            } else {
                // De-dup by id to handle overlaps at page boundaries.
                val existingIds = entry.list.items.map { it.id }.toSet()
                entry.list.items + newItems.filter { it.id !in existingIds }
            }
            val visibleRows = computeVisibleRows(combined)
            val sel = if (isFirstPage) 0 else entry.list.selectedIndex.coerceIn(0, maxOf(0, combined.lastIndex))
            val first = if (isFirstPage) 0 else entry.list.firstVisibleIndex.coerceIn(0, maxOf(0, combined.size - visibleRows))
            entry.copy(
                list = entry.list.copy(
                    items = combined,
                    isLoading = false,
                    error = null,
                    visibleRows = visibleRows,
                    selectedIndex = sel,
                    firstVisibleIndex = first,
                    hasMore = hasMore,
                ),
            )
        }
    }

    /** Fresh first page for a playlist shown from the cache; replaces the rows if it differs. */
    private fun reconcilePlaylistRows(playlistId: String, playlistUri: String, cachedUris: List<String>, key: String) {
        viewModelScope.launch {
            val fresh = library.reconcilePlaylist(playlistId, cachedUris) ?: return@launch
            nextOffsetByKey[key] = fresh.nextOffset
            val occurrences = HashMap<String, Int>()
            val rows = fresh.tracks.map { track ->
                val n = occurrences[track.uri] ?: 0
                occurrences[track.uri] = n + 1
                LcdItem(
                    id = "${track.uri}#$n",
                    title = LcdLabel.Text(track.name),
                    subtitle = LcdLabel.Text(track.allArtists),
                )
            }
            retopItemsPreservingHighlight(IPodScreen.PlaylistTracks(playlistId, playlistUri), rows, hasMore = fresh.hasMore)
        }
    }

    /**
     * Replace the top entry's rows while keeping the highlight on the same row id and the window
     * moving with it — a reconcile that prepends new songs must not yank the list under the user.
     */
    private fun retopItemsPreservingHighlight(expectedScreen: IPodScreen, items: List<LcdItem>, hasMore: Boolean? = null) {
        updateTopEntry(expectedScreen) { entry ->
            val visibleRows = computeVisibleRows(items)
            val oldSelectedId = entry.list.items.getOrNull(entry.list.selectedIndex)?.id
            val newSelected = oldSelectedId?.let { id -> items.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: entry.list.selectedIndex.coerceIn(0, maxOf(0, items.lastIndex))
            val delta = newSelected - entry.list.selectedIndex
            val first = (entry.list.firstVisibleIndex + delta).coerceIn(0, maxOf(0, items.size - visibleRows))
            entry.copy(
                list = entry.list.copy(
                    items = items,
                    isLoading = false,
                    error = null,
                    visibleRows = visibleRows,
                    selectedIndex = newSelected,
                    firstVisibleIndex = first,
                    hasMore = hasMore ?: entry.list.hasMore,
                ),
            )
        }
    }

    /** Set an error on the top entry. */
    private fun setTopError(expectedScreen: IPodScreen, error: LcdLabel) {
        updateTopEntry(expectedScreen) { entry ->
            entry.copy(list = entry.list.copy(isLoading = false, error = error))
        }
    }

    /**
     * Update the top entry of the stack if it matches the expected screen. Safe against a race
     * where the user has already backed out by the time the async load finishes.
     */
    private fun updateTopEntry(
        expectedScreen: IPodScreen,
        transform: (IPodStackEntry) -> IPodStackEntry,
    ) {
        _uiState.update { state ->
            val top = state.stack.lastOrNull() ?: return@update state
            if (top.screen != expectedScreen) return@update state
            state.copy(stack = state.stack.dropLast(1) + transform(top))
        }
    }

    /**
     * Rebuild the main menu when the now-playing presence flips, preserving the highlight on the
     * same item id. Only called from the player-state collector when [hasNowPlaying] changes.
     */
    private fun rebuildMainMenuIfOnTop(state: IPodUiState, hasNowPlaying: Boolean): IPodUiState {
        val bottomEntry = state.stack.firstOrNull() ?: return state
        if (bottomEntry.screen !is IPodScreen.MainMenu) return state
        val oldItems = bottomEntry.list.items
        val oldSelectedId = oldItems.getOrNull(bottomEntry.list.selectedIndex)?.id
        val newItems = buildMainMenu(hasNowPlaying)
        val newIndex = if (oldSelectedId != null) {
            newItems.indexOfFirst { it.id == oldSelectedId }.coerceAtLeast(0)
        } else {
            0
        }
        val visibleRows = computeVisibleRows(newItems)
        var first = bottomEntry.list.firstVisibleIndex
        // Clamp the window for the new list.
        if (newIndex >= first + visibleRows) first = newIndex - visibleRows + 1
        if (newIndex < first) first = newIndex
        first = first.coerceIn(0, maxOf(0, newItems.size - visibleRows))

        val updatedEntry = bottomEntry.copy(
            list = bottomEntry.list.copy(
                items = newItems,
                selectedIndex = newIndex,
                firstVisibleIndex = first,
                visibleRows = visibleRows,
            ),
        )
        // The main menu is always at index 0 in the stack.
        return state.copy(stack = listOf(updatedEntry) + state.stack.drop(1))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Determines the row count: TWO_LINE if any item has a subtitle, SINGLE_LINE otherwise. */
    private fun computeVisibleRows(items: List<LcdItem>): Int =
        if (items.any { it.subtitle != null }) LCD_ROWS_TWO_LINE else LCD_ROWS_SINGLE_LINE

    // ── Menu builders ─────────────────────────────────────────────────────────

    companion object {
        fun buildMainMenu(hasNowPlaying: Boolean): List<LcdItem> = buildList {
            add(LcdItem(id = "coverflow", title = LcdLabel.Res(R.string.ipod_menu_cover_flow), hasSubmenu = true))
            add(LcdItem(id = "music", title = LcdLabel.Res(R.string.ipod_menu_music), hasSubmenu = true))
            add(LcdItem(id = "albums", title = LcdLabel.Res(R.string.ipod_menu_albums), hasSubmenu = true))
            add(LcdItem(id = "artists", title = LcdLabel.Res(R.string.ipod_menu_artists), hasSubmenu = true))
            add(LcdItem(id = "playlists", title = LcdLabel.Res(R.string.ipod_menu_playlists), hasSubmenu = true))
            add(LcdItem(id = "podcasts", title = LcdLabel.Res(R.string.ipod_menu_podcasts), hasSubmenu = true))
            add(LcdItem(id = "shuffle", title = LcdLabel.Res(R.string.ipod_menu_shuffle_songs)))
            if (hasNowPlaying) {
                add(LcdItem(id = "nowplaying", title = LcdLabel.Res(R.string.ipod_menu_now_playing), hasSubmenu = true))
            }
            add(LcdItem(id = "settings", title = LcdLabel.Res(R.string.ipod_menu_settings), hasSubmenu = true))
        }

        fun buildSettingsItems(config: ClickSoundsConfig, bodyColor: IPodBodyColor): List<LcdItem> = listOf(
            LcdItem(
                id = "clicksounds",
                title = LcdLabel.Res(R.string.ipod_settings_click_sounds),
                value = LcdLabel.Res(if (config.enabled) R.string.ipod_value_on else R.string.ipod_value_off),
            ),
            LcdItem(
                id = "clickvolume",
                title = LcdLabel.Res(R.string.ipod_settings_click_volume),
                value = LcdLabel.ResArgs(R.string.ipod_value_percent, listOf(config.volumePercent)),
            ),
            LcdItem(
                id = "clickpitch",
                title = LcdLabel.Res(R.string.ipod_settings_click_pitch),
                value = LcdLabel.Res(
                    when (config.pitch) {
                        ClickPitch.LOW -> R.string.ipod_value_low
                        ClickPitch.MEDIUM -> R.string.ipod_value_medium
                        ClickPitch.HIGH -> R.string.ipod_value_high
                    },
                ),
            ),
            LcdItem(
                id = "bodycolor",
                title = LcdLabel.Res(R.string.ipod_settings_color),
                value = LcdLabel.Res(
                    when (bodyColor) {
                        IPodBodyColor.SILVER -> R.string.ipod_value_silver
                        IPodBodyColor.BLACK -> R.string.ipod_value_black
                    },
                ),
            ),
            LcdItem(
                id = "ipodmode",
                title = LcdLabel.Res(R.string.ipod_settings_mode),
                value = LcdLabel.Res(R.string.ipod_value_on),
            ),
        )
    }
}

class IPodViewModelFactory(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IPodViewModel(
            settingsRepository = container.settingsRepository,
            libraryCache       = container.libraryCache,
            repository         = container.spotifyRepository,
            playerStateManager = container.playerStateManager,
            audioManager       = appContext.getSystemService(AudioManager::class.java),
            likedSongsIndexer  = container.likedSongsIndexer,
            imageLoader        = container.imageLoader,
            appContext         = appContext,
        ) as T
}
