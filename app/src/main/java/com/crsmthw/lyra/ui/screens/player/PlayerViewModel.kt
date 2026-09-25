package com.crsmthw.lyra.ui.screens.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlaybackOrigin
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.player.WakeRestoreBody
import com.crsmthw.lyra.data.player.isCollectionContext
import com.crsmthw.lyra.data.player.pickLocalDevice
import com.crsmthw.lyra.data.player.planWakeRestore
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.LyricsRepository
import com.crsmthw.lyra.data.repository.LyricsState
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.ui.cassette.CassetteAlbumMeta
import com.crsmthw.lyra.ui.cassette.CassetteLabel
import com.crsmthw.lyra.ui.cassette.CassetteSettings
import com.crsmthw.lyra.ui.cassette.cassetteAlbumIdFor
import com.crsmthw.lyra.ui.cassette.cassetteAlbumMetaFrom
import com.crsmthw.lyra.ui.cassette.cassetteLabelFor
import com.crsmthw.lyra.ui.components.TrackActionsController
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.LyricLine
import com.crsmthw.lyra.util.visualizer.VisualizerManager
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlayerVM"

/**
 * How long to let a freshly App-Remote-started item load before seeking into it. `playerApi.play()`
 * is fire-and-forget IPC (the SDK's `CallResult` is discarded), so the call returning says only
 * that Spotify received it; a seek that arrives before the item is loaded is dropped and the item
 * plays from 0:00. It does not disturb the restore loop's clock: `sdkStartedAt` is captured after
 * this delay, and an episode's restore call sends no `position_ms` anyway.
 */
private const val SDK_SEEK_SETTLE_MS = 1_200L

/**
 * Settle time between `remoteManager.setShuffle(...)` and the subsequent `connectAndPlay(...)` in
 * the App Remote fallback path. `playerApi.setShuffle` is fire-and-forget IPC — it returns on
 * DISPATCH, not completion. If the play arrives before shuffle takes effect the first item is still
 * picked at random (the exact bug the user reports). Tunable on device.
 */
private const val REMOTE_SHUFFLE_SETTLE_MS = 300L

/**
 * The wake restore's device wait (docs/PLAYER.md → Playback 404 Fallback): `me/player/devices` every
 * [WAKE_DEVICE_POLL_MS] until this phone is LISTED, for at most [WAKE_DEVICE_TIMEOUT_MS] after the
 * SDK play — then one body without `device_id`. Two seconds keeps a minute's wait at ~30 calls.
 */
private const val WAKE_DEVICE_POLL_MS    = 2_000L
private const val WAKE_DEVICE_TIMEOUT_MS = 60_000L

/**
 * After the body is accepted, `me/player` every [WAKE_CONFIRM_POLL_MS] until Spotify REPORTS the
 * song playing (the only thing that clears the waking state), for at most [WAKE_CONFIRM_TIMEOUT_MS].
 * 700 ms is `fetchUntilTrackChanges`' cadence.
 */
private const val WAKE_CONFIRM_POLL_MS    = 700L
private const val WAKE_CONFIRM_TIMEOUT_MS = 8_000L

/**
 * How many times the wake body may fail with an error that is NOT 404 (listed but not ready), NOT
 * 429 (abandon) and NOT a body refusal (degrade) — a 5xx, a "Restriction violated" 403 on a context
 * body — before the restore gives up. Without a cap such a body was re-sent every
 * [WAKE_DEVICE_POLL_MS] for the whole [WAKE_DEVICE_TIMEOUT_MS].
 */
private const val WAKE_MAX_BODY_FAILURES = 3

/** The shuffle bracket's re-assert delay after a successful play — `shuffleContext`'s 1.5 s. */
private const val SHUFFLE_REASSERT_DELAY_MS = 1_500L

/**
 * A shuffle OFF sent right before a `uris` play must have TAKEN EFFECT before the play goes out —
 * "the order of execution is not guaranteed when you use this API with other Player API endpoints"
 * (Toggle Playback Shuffle reference). Device pass 2026-09-25: the first liked tap of a fresh
 * process played the wrong song although the bracket had run (both PUTs left on cold connections
 * and the play was applied first). So `me/player` is re-read every [SHUFFLE_CONFIRM_POLL_MS] until
 * it reports the requested state, for at most [SHUFFLE_CONFIRM_TIMEOUT_MS]; at the ceiling the play
 * goes out anyway (a wrong song beats no song).
 */
private const val SHUFFLE_CONFIRM_POLL_MS    = 250L
private const val SHUFFLE_CONFIRM_TIMEOUT_MS = 1_500L

/** How many albums' cassette fine print the player keeps (one small entry per album id). */
private const val CASSETTE_META_CACHE_SIZE = 24

/**
 * "No active device" — `me/player/play` answers 404 when Spotify is not running anywhere. It is the
 * trigger for the App Remote fallback, NOT a rejection of the request body, so nothing may degrade
 * on it. Matches the long-standing `message.contains("404")` test: `SpotifyRepository.safeCall`
 * flattens HTTP failures to `"HTTP <code>: <body>"` (docs/SPOTIFY.md → Rate Limiting).
 */
private fun Throwable.isNoActiveDevice(): Boolean = message?.contains("404") == true

/**
 * True when the API REFUSED THIS REQUEST BODY — read off the `"HTTP <code>: …"` text `safeCall`
 * produces. The degrade it gates (drop a multi-uri `uris` body down to the single uri, losing the
 * queue) is only ever the right answer when the body itself is what the API objected to: a 413 /
 * 414 / 422 from a 750-uri list genuinely is that, and must still degrade.
 *
 * Four things are explicitly NOT a verdict on the body, and none of them may cost the user the
 * queue:
 *  - **no status at all** (no connectivity, a parse error) — nothing was refused;
 *  - **404** — "no active device"; the App Remote fallback is what fixes it (see [isNoActiveDevice]);
 *  - **429** — rate limited; the same body a moment later is fine. Back off instead
 *    ([isRateLimited] → `PlayerStateManager.noteRateLimited()`), never degrade;
 *  - **401** — the token was refused, not the body. `TokenManager` has already refreshed and
 *    retried by the time this is seen.
 */
private fun Throwable.isRequestRefused(): Boolean {
    val status = message?.takeIf { it.startsWith("HTTP ") }
        ?.drop("HTTP ".length)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: return false
    return status in 400..499 && status !in setOf(401, 404, 429)
}

/**
 * Spotify's rate limit (429). Same `"HTTP <code>: …"` shape, same `message.contains` test the
 * device/volume paths use (docs/SPOTIFY.md → Rate Limiting): every caller that sees one must call
 * `PlayerStateManager.noteRateLimited()` so the whole app shares the one 60-second penalty window
 * instead of hammering independently.
 */
private fun Throwable.isRateLimited(): Boolean = message?.contains("429") == true

enum class RepeatMode { OFF, CONTEXT, TRACK }

sealed class AddToPlaylistResult {
    data class Added(val playlistName: String) : AddToPlaylistResult()
    data class Removed(val playlistName: String) : AddToPlaylistResult()
    data object NeedsReconnect : AddToPlaylistResult()
    data class Error(val message: String?) : AddToPlaylistResult()
}

data class PlaylistPickerState(
    val playlists              : List<SpotifyPlaylist> = emptyList(),
    val isLoading              : Boolean               = false,
    val containingPlaylistIds  : Set<String>           = emptySet(),
    val addResult              : AddToPlaylistResult?  = null,
    val isCreatingPlaylist     : Boolean               = false,
    val createPlaylistError    : String?               = null,
)

data class PlayerUiState(
    val isPlaying             : Boolean        = false,
    val currentTrack          : SpotifyTrack?  = null,
    val progressMs            : Long           = 0L,
    val durationMs            : Long           = 0L,
    val shuffleEnabled        : Boolean        = false,
    val repeatMode            : RepeatMode     = RepeatMode.OFF,
    val isLiked               : Boolean        = false,
    val sleepTimerMinutes     : Int            = 0,
    val sleepTimerTotalMinutes: Int            = 0,
    val error                 : String?        = null,
    val isWakingUp            : Boolean        = false,
    val currentDevice         : SpotifyDevice? = null,
    val devicePickerLoading   : Boolean        = false,
    val availableDevices      : List<SpotifyDevice> = emptyList(),
    val devicePickerError     : String?        = null,
    val deviceTransferError   : String?        = null,
    val lyricsMode            : Boolean        = false,
    val lyricsState           : LyricsState    = LyricsState.None,
    val currentLyricLineIndex : Int            = -1,
    val visualizerEnabled     : Boolean        = false,
    val visualizerStyle       : VisualizerStyle = VisualizerStyle.BOTH,
) {
    val progress: Float
        get() = if (durationMs > 0L) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(
    private val playerStateManager: PlayerStateManager,
    private val repository        : SpotifyRepository,
    private val remoteManager     : SpotifyRemoteManager,
    private val libraryCache      : LibraryCache,
    private val lyricsRepository  : LyricsRepository,
    private val settingsRepository: SettingsRepository,
    private val visualizerManager : VisualizerManager,
    /** This phone's likely Spotify Connect names (Settings `device_name`, `Build.MODEL`) — see [pickLocalDevice]. */
    private val deviceNameHints   : () -> List<String>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    // Narrow derived flows for composables that must NOT recompose on every poll tick (the
    // Library layouts, the panel host). As StateFlows they carry their CURRENT value, so
    // `collectAsStateWithLifecycle()` seeds the first frame from it — the reason the call sites
    // used to read `uiState.value` for an initial value (lint StateFlowValueCalledInComposition).
    val hasCurrentTrack: StateFlow<Boolean> = _uiState.map { it.currentTrack != null }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.currentTrack != null)
    val currentTrackId: StateFlow<String?> = _uiState.map { it.currentTrack?.id }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.currentTrack?.id)
    val isPlayingFlow: StateFlow<Boolean> = _uiState.map { it.isPlaying }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.isPlaying)

    // ── Cassette idle screen (docs/CASSETTE.md) ──────────────────────────────
    /** Every cassette preference, Eagerly so the no-argument collect seeds from the live value. */
    val cassetteSettings: StateFlow<CassetteSettings> = settingsRepository.cassetteSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, CassetteSettings())

    /**
     * The album fine print (copyright line + record label) by album id — ONE `GET albums/{id}` per
     * album, LRU-bounded. Only SUCCESSES are cached (a lookup whose album simply has no copyright
     * caches as a meta of nulls); a failure is not, so it is retried when that album comes back —
     * but never per poll tick, because the fetch below is driven by a `distinctUntilChanged` album
     * id, not by the 3 s poll. Touched only on the main dispatcher (viewModelScope).
     */
    private val cassetteAlbumMeta = object : LinkedHashMap<String, CassetteAlbumMeta>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CassetteAlbumMeta>?) =
            size > CASSETTE_META_CACHE_SIZE
    }
    /** Bumped whenever [cassetteAlbumMeta] gains an entry, so [cassetteLabel] re-reads it. */
    private val cassetteMetaVersion = MutableStateFlow(0)

    /**
     * What the cassette's label prints for the current item: emitted WITHOUT the fine print at once
     * and again when the album lookup lands. Null while nothing is playing.
     */
    val cassetteLabel: StateFlow<CassetteLabel?> = combine(
        _uiState.map { it.currentTrack }.distinctUntilChanged(),
        cassetteMetaVersion,
    ) { track, _ ->
        track?.let { t -> cassetteLabelFor(t, cassetteAlbumIdFor(t)?.let { cassetteAlbumMeta[it] }) }
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _cassetteOwnsBack = MutableStateFlow(false)
    /**
     * True while the full player's cassette overlay is up, i.e. while ITS back handler — not
     * NavHost's pop — owns the back gesture. `LyraNavGraph` reads it: `PlayerPanelHost` watches
     * every predictive back gesture passively (whichever handler won it) and would otherwise seek
     * the mini bar in over the cassette for a pop that never happens, then unwind an abandoned
     * seek. Written only by the ROUTE PlayerScreen (never the docked pane, which has no cassette).
     */
    val cassetteOwnsBack: StateFlow<Boolean> = _cassetteOwnsBack

    /** PlayerScreen mirrors its local `cassetteVisible` here; see [cassetteOwnsBack]. */
    fun setCassetteOwnsBack(owns: Boolean) {
        _cassetteOwnsBack.value = owns
    }

    /** Shared add-to-playlist implementation (the same one the song touch-and-hold menu uses),
     *  targeting the current track. The picker methods below are thin delegations to it. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)
    val pickerState: StateFlow<PlaylistPickerState> get() = trackActions.pickerState

    // Fallback timer used when the wake path goes through LibraryViewModel (no callback available).
    // playTrack / playPause / skip paths cancel this job and clear explicitly via clearIsWakingUp().
    private var clearWakingUpJob: Job? = null
    private var lyricsJob: Job? = null

    /**
     * The play in flight (a tap, or the play button's restore). A new one cancels it: a wake
     * restore can wait a minute for its device, and must never send its body over a newer choice.
     * (The play-request generation covers the paths that do not go through here.)
     */
    private var playbackJob: Job? = null

    /**
     * How many [restoreAfterWake] calls are running (main thread only). While non-zero the
     * `connecting` collector does not arm its 3.5 s fallback clear — Cris's rule: the waking state
     * lasts until Spotify reports the song playing, and a restore's own connect would otherwise
     * start a timer that clears it mid-wait.
     */
    private var ownedWakeRestores = 0

    /**
     * Which VM-issued play currently OWNS the waking state (main thread only): bumped by every
     * [startPlay] and every play-button restore. A superseded restore clears the waking state only
     * if it is still the owner — i.e. what superseded it was a pause, a skip or a Library /
     * shuffle play, none of which clears a waking state it did not set — and leaves it alone when
     * a newer play here has taken it over (that play clears it when ITS song is reported playing).
     */
    private var wakingOwner = 0L

    /** Installed as `PlayerStateManager.onWakeRestore`; kept so [onCleared] removes only its own. */
    private val wakeRestoreHook: suspend (SpotifyTrack, Long) -> Boolean =
        { track, pausedProgressMs -> runPlayButtonRestore(track, pausedProgressMs) }

    private fun clearIsWakingUp() {
        clearWakingUpJob?.cancel()
        playerStateManager.ensureTickRunning()
        _uiState.update { it.copy(isWakingUp = false) }
    }

    init {
        viewModelScope.launch {
            playerStateManager.state.collect { state ->
                val prevTrack = _uiState.value.currentTrack
                _uiState.update { ui ->
                    ui.copy(
                        isPlaying              = state.isPlaying,
                        currentTrack           = state.currentTrack,
                        progressMs             = state.progressMs,
                        durationMs             = state.durationMs,
                        shuffleEnabled         = state.shuffleEnabled,
                        repeatMode             = when (state.repeatState) {
                            "track"   -> RepeatMode.TRACK
                            "context" -> RepeatMode.CONTEXT
                            else      -> RepeatMode.OFF
                        },
                        sleepTimerMinutes      = state.sleepTimerMinutes,
                        sleepTimerTotalMinutes = state.sleepTimerTotalMinutes,
                        currentDevice          = state.currentDevice,
                        error                  = null,
                        currentLyricLineIndex  = computeCurrentLine(state.progressMs, ui.lyricsState),
                        // isWakingUp is intentionally NOT cleared here — clearing is explicit.
                        // playTrack() clears directly; playPause/skip use onWakeOperationComplete;
                        // library-play falls back to the 3.5s timer below.
                    )
                }
                val newTrack = state.currentTrack
                if (newTrack != null && newTrack.id != prevTrack?.id) {
                    // A podcast episode is not a track: `me/tracks/contains` with an episode id
                    // answers about a DIFFERENT (or non-existent) track, so the heart would show
                    // someone else's saved state. The like affordance is hidden for episodes;
                    // this keeps the flag honest behind it.
                    if (newTrack.isEpisode) _uiState.update { it.copy(isLiked = false) }
                    else checkIsLiked(newTrack.id)
                    fetchLyricsForTrack(newTrack, state.durationMs)
                } else if (newTrack == null && prevTrack != null) {
                    lyricsJob?.cancel()
                    _uiState.update { it.copy(lyricsState = LyricsState.None, currentLyricLineIndex = -1) }
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.lyricsMode.collect { enabled ->
                _uiState.update { it.copy(lyricsMode = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.visualizerEnabled.collect { enabled ->
                _uiState.update { it.copy(visualizerEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.visualizerStyle.collect { style ->
                _uiState.update { it.copy(visualizerStyle = style) }
            }
        }
        // Gate visualizer capture on isPlaying && visualizerEnabled so the capture
        // (and its RECORD_AUDIO usage + CPU) only runs when actually needed. Note:
        // Visualizer(0) taps the output mix, not the microphone, so it does NOT raise
        // the mic privacy indicator despite requiring the RECORD_AUDIO permission.
        viewModelScope.launch {
            combine(
                playerStateManager.state.map { it.isPlaying }.distinctUntilChanged(),
                settingsRepository.visualizerEnabled,
            ) { isPlaying, enabled -> isPlaying && enabled }
            .distinctUntilChanged()
            .collect { active ->
                if (active) {
                    visualizerManager.tryInitialize()
                    visualizerManager.start()
                } else {
                    visualizerManager.stop()
                }
            }
        }
        viewModelScope.launch {
            remoteManager.connecting.collect { connecting ->
                if (connecting) {
                    clearWakingUpJob?.cancel()
                    _uiState.update { it.copy(isWakingUp = true) }
                } else if (ownedWakeRestores == 0) {
                    // Fallback: library-play (LibraryViewModel) calls the SDK with no callback.
                    // For playTrack/playPause/skip the explicit clear will cancel this before it
                    // fires; a running restoreAfterWake never arms it (see ownedWakeRestores).
                    clearWakingUpJob = viewModelScope.launch {
                        delay(3_500L)
                        if (ownedWakeRestores == 0) _uiState.update { it.copy(isWakingUp = false) }
                    }
                }
            }
        }
        // PlayerStateManager calls these at the start and end of each SDK 404 path.
        // onWakeOperationStart fires even when connectSuspend() short-circuits on a stale
        // connection — ensuring isWakingUp=true shows through the position-restore loop.
        playerStateManager.onWakeOperationStart = {
            viewModelScope.launch { _uiState.update { it.copy(isWakingUp = true) } }
        }
        playerStateManager.onWakeOperationComplete = {
            viewModelScope.launch { clearIsWakingUp() }
        }
        // The play button's wake restore — the same restoreAfterWake a tap uses (§ playPause).
        playerStateManager.onWakeRestore = wakeRestoreHook
        // The cassette's album fine print. Anti-bloat: NO album call while the feature is off —
        // the id is null then, and flipping the feature ON with a track playing emits that track's
        // album id, which fetches it. Episodes and local files have no id (cassetteAlbumIdFor).
        // `collectLatest` drops an in-flight lookup the moment the album changes.
        viewModelScope.launch {
            combine(
                cassetteSettings.map { it.enabled }.distinctUntilChanged(),
                _uiState.map { ui -> ui.currentTrack?.let(::cassetteAlbumIdFor) }.distinctUntilChanged(),
            ) { enabled, albumId -> if (enabled) albumId else null }
                .distinctUntilChanged()
                .collectLatest { albumId ->
                    if (albumId == null || cassetteAlbumMeta.containsKey(albumId)) return@collectLatest
                    // Inside the app-wide 429 penalty window the call would only be refused again;
                    // the label prints its boilerplate, and the album is retried when it comes back.
                    if (playerStateManager.isRateLimited()) return@collectLatest
                    val result = repository.getAlbum(albumId)
                    // safeCall's runCatching turns a cancellation into a failure: stop here if so.
                    currentCoroutineContext().ensureActive()
                    result.fold(
                        onSuccess = { album ->
                            cassetteAlbumMeta[albumId] = cassetteAlbumMetaFrom(album)
                            cassetteMetaVersion.update { it + 1 }
                        },
                        onFailure = { e ->
                            if (e.isRateLimited()) playerStateManager.noteRateLimited()
                            Log.w(TAG, "cassette album meta for $albumId failed: ${e.message}")
                        },
                    )
                }
        }
        remoteManager.connect(onConnected = { }, onFailure = { })
    }

    override fun onCleared() {
        // The manager is app-scoped: a dead ViewModel's hook would leak it and restore into it.
        if (playerStateManager.onWakeRestore === wakeRestoreHook) playerStateManager.onWakeRestore = null
    }

    private suspend fun checkIsLiked(trackId: String) {
        repository.isTrackSaved(trackId).fold(
            onSuccess = { liked -> _uiState.update { it.copy(isLiked = liked) } },
            onFailure = { },
        )
    }

    /**
     * Re-reads the saved state for the item the full player is showing (it composes with whatever
     * `currentTrack` already is, so it can arrive after the observer's own check).
     *
     * Takes the ITEM, not an id: an episode id is indistinguishable from a track id, and
     * `me/library/contains` with `spotify:track:<episode id>` asks about a different (or
     * non-existent) track — the same reason the observer, `toggleLike` and `playTrack` all branch
     * on [SpotifyTrack.isEpisode]. The like affordance is hidden for episodes, so there is nothing
     * to refresh behind it.
     */
    fun recheckLiked(track: SpotifyTrack) {
        if (track.isEpisode) return
        viewModelScope.launch { checkIsLiked(track.id) }
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    // ── Visualizer ────────────────────────────────────────────────────────────

    fun setVisualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVisualizerEnabled(enabled) }
    }

    fun onRecordAudioGranted() {
        viewModelScope.launch {
            visualizerManager.tryInitialize()
            settingsRepository.setVisualizerEnabled(true)
        }
    }

    fun toggleLyricsMode() {
        viewModelScope.launch { settingsRepository.setLyricsMode(!_uiState.value.lyricsMode) }
    }

    private fun fetchLyricsForTrack(track: SpotifyTrack, durationMs: Long) {
        // Never query LRCLIB for a podcast episode: it is a LYRICS database, so an episode title
        // can only ever produce a wrong match or a wasted round trip.
        //
        // NOTE the raw `artists` read below, deliberately NOT `primaryArtist`: that property now
        // falls back to the show's name for an episode, and "tidying" this line into it would be
        // exactly how a show name reaches LRCLIB. The explicit guard is what makes this safe,
        // not the null check that happens to follow it.
        lyricsJob?.cancel()
        if (track.isEpisode) {
            _uiState.update { it.copy(lyricsState = LyricsState.None, currentLyricLineIndex = -1) }
            return
        }
        val artistName = track.artists?.firstOrNull()?.name
        if (artistName == null) {
            _uiState.update { it.copy(lyricsState = LyricsState.None, currentLyricLineIndex = -1) }
            return
        }
        val albumName = track.album?.name.orEmpty()
        _uiState.update { it.copy(lyricsState = LyricsState.Loading, currentLyricLineIndex = -1) }
        lyricsJob = viewModelScope.launch {
            delay(500L)
            val result = lyricsRepository.fetchLyrics(
                trackId    = track.id,
                trackName  = track.name,
                artistName = artistName,
                albumName  = albumName,
                durationMs = durationMs,
            )
            _uiState.update { ui ->
                ui.copy(
                    lyricsState           = result,
                    currentLyricLineIndex = computeCurrentLine(ui.progressMs, result),
                )
            }
        }
    }

    private fun computeCurrentLine(progressMs: Long, lyricsState: LyricsState): Int {
        if (lyricsState !is LyricsState.Synced) return -1
        val lines = lyricsState.lines
        if (lines.isEmpty()) return -1
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].timestampMs <= progressMs) idx = i else break
        }
        return idx
    }

    // ── Controls (delegate to PlayerStateManager) ─────────────────────────────

    fun playPause()            = playerStateManager.playPause()
    fun skipNext()             = playerStateManager.skipNext()
    fun skipPrevious()         = playerStateManager.skipPrevious()
    fun seekTo(fraction: Float)= playerStateManager.seekTo(fraction)
    fun toggleShuffle()        = playerStateManager.toggleShuffle()
    fun cycleRepeat()          = playerStateManager.cycleRepeat()
    fun setSleepTimer(m: Int)  = playerStateManager.setSleepTimer(m)

    // ── Like ──────────────────────────────────────────────────────────────────

    fun toggleLike() {
        val state    = _uiState.value
        val track    = state.currentTrack ?: return
        if (track.isEpisode) return   // the like affordance is hidden for episodes; belt and braces
        val trackId  = track.id
        val newLiked = !state.isLiked
        _uiState.update { it.copy(isLiked = newLiked) }
        viewModelScope.launch {
            if (newLiked) {
                repository.saveTrack(trackId)
                withContext(Dispatchers.IO) { libraryCache.prependToLikedSongs(track) }
            } else {
                repository.removeTrack(trackId)
                withContext(Dispatchers.IO) { libraryCache.removeFromLikedSongs(trackId) }
            }
        }
    }

    /**
     * Sets the like state for a track identified by [uri] to the TARGET [liked]. Used by the Classic's
     * options menu, where the track may or may not be the current one. Mirrors [toggleLike]'s
     * behaviour: optimistic UI update (only when [uri] IS the current track), server call, cache
     * patch. Never for an episode uri.
     */
    fun setLiked(uri: String, liked: Boolean, track: SpotifyTrack? = null) {
        if (uri.startsWith("spotify:episode:")) return
        val trackId = uri.substringAfterLast(':')
        // Optimistic UI flip — only when the uri matches what the player is showing.
        val currentTrack = _uiState.value.currentTrack?.takeIf { it.uri == uri }
        if (currentTrack != null) {
            _uiState.update { it.copy(isLiked = liked) }
        }
        // The full track for the liked-songs cache patch: the caller's (the iLyra has it for a
        // liked-list pick even while our currentTrack lags behind), else ours when it matches.
        val fullTrack = track?.takeIf { it.uri == uri } ?: currentTrack
        viewModelScope.launch {
            if (liked) {
                repository.saveTrack(trackId)
                if (fullTrack != null) {
                    withContext(Dispatchers.IO) { libraryCache.prependToLikedSongs(fullTrack) }
                }
            } else {
                repository.removeTrack(trackId)
                withContext(Dispatchers.IO) { libraryCache.removeFromLikedSongs(trackId) }
            }
        }
    }

    /**
     * Adds a track to a playlist by uri. Used by the Classic's add-to-playlist screen. Mirrors
     * [TrackActionsController.togglePlaylistTrack]'s ADD branch: server POST, cache row append
     * (when the full track is known and the cache holds the complete list — the guard inside
     * [LibraryCache.appendToPlaylistTrackList] checks), mutation announcement for the Library's
     * count reconcile. Never for an episode uri. Failures are logged, not surfaced.
     */
    fun addToPlaylist(playlistId: String, trackUri: String, trackCount: Int? = null, track: SpotifyTrack? = null) {
        if (trackUri.startsWith("spotify:episode:")) return
        val fullTrack = track?.takeIf { it.uri == trackUri }
            ?: _uiState.value.currentTrack?.takeIf { it.uri == trackUri }
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackUri).fold(
                onSuccess = {
                    withContext(Dispatchers.IO) {
                        if (fullTrack != null) {
                            // The count the caller's row showed, else the cached metadata's; unknown
                            // → treat the cache as a prefix and only announce the mutation.
                            val knownTotal = trackCount
                                ?: libraryCache.load()?.playlists
                                    ?.firstOrNull { it.id == playlistId }?.trackCount
                                ?: Int.MAX_VALUE
                            libraryCache.appendToPlaylistTrackList(
                                playlistId, knownTotal, fullTrack,
                            )
                        } else {
                            libraryCache.notePlaylistMutated(playlistId)
                        }
                    }
                },
                onFailure = { e -> Log.w(TAG, "addToPlaylist failed: ${e.message}") },
            )
        }
    }

    fun playFromLikedSongs(trackUri: String, shuffle: Boolean? = null) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                // distinctBy { it.id }: guard the play queue against a not-yet-healed cache that may
                // still hold duplicate liked songs (see LibraryViewModel pagination dedup).
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.distinctBy { it.id }
            }
            // Origin = Liked either way: a restore rebuilds the window from the liked cache at that
            // time (the collection context rejects an offset, so it is never sent as a context).
            // A uri missing from the cache (not indexed yet) plays alone: a window from the TOP of
            // the list would lead with a different song, and a uris body plays its head.
            val idx = cached?.indexOfFirst { it.uri == trackUri } ?: -1
            startPlay(
                uri             = trackUri,
                contextUri      = null,
                uris            = cached?.takeIf { idx >= 0 }
                    ?.drop(idx)?.map { it.uri }?.take(PlaybackOrigin.URI_CAP),
                startPositionMs = null,
                shuffle         = shuffle,
                origin          = PlaybackOrigin.Liked,
            )
        }
    }

    // ── Playlist picker (thin delegations to the shared TrackActionsController) ──

    fun loadOwnedPlaylists() {
        val track = _uiState.value.currentTrack ?: return
        // Playlists hold tracks. The picker's membership check and its add/remove calls are all
        // track-only, so an episode must never reach it (the button is disabled too).
        if (track.isEpisode) return
        trackActions.openPlaylistPickerFor(track.toTrackActionTarget())
    }

    fun togglePlaylistTrack(playlist: SpotifyPlaylist) = trackActions.togglePlaylistTrack(playlist)

    fun createPlaylist(name: String, description: String, isPublic: Boolean) =
        trackActions.createPlaylist(name, description, isPublic)

    fun clearPickerResult() = trackActions.clearPickerResult()

    // ── Device picker ──────────────────────────────────────────────────────────

    fun loadAvailableDevices() {
        _uiState.update { it.copy(devicePickerLoading = true, devicePickerError = null) }
        viewModelScope.launch {
            repository.getAvailableDevices().fold(
                onSuccess = { devices ->
                    _uiState.update { it.copy(devicePickerLoading = false, availableDevices = devices) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(devicePickerLoading = false, devicePickerError = e.message) }
                },
            )
        }
    }

    fun transferToDevice(deviceId: String) {
        if (playerStateManager.isRateLimited()) {
            _uiState.update { it.copy(deviceTransferError = "Rate limited — please wait a moment.") }
            return
        }
        playerStateManager.lockIsPlaying()
        playerStateManager.lockTrack()
        viewModelScope.launch {
            repository.transferPlayback(deviceId).fold(
                onSuccess = {
                    delay(700L)
                    playerStateManager.fetchOnce()
                },
                onFailure = { e ->
                    if (e.message?.contains("429") == true) playerStateManager.noteRateLimited()
                    _uiState.update { it.copy(deviceTransferError = e.message) }
                },
            )
        }
    }

    fun transferToThisDevice() {
        val track = _uiState.value.currentTrack ?: return
        playerStateManager.lockIsPlaying()
        playerStateManager.lockTrack()
        viewModelScope.launch {
            remoteManager.connectAndPlay(track.uri)
            delay(700L)
            playerStateManager.fetchOnce()
        }
    }

    // Debounced so a slider drag-release or a burst of ± taps coalesces into one PUT,
    // keeping us well under the Web API rate limit. Controls whichever device is active.
    private var volumeJob: Job? = null

    fun setVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            delay(250L)
            repository.setVolume(clamped).fold(
                onSuccess = {},
                onFailure = { e ->
                    if (e.message?.contains("429") == true) playerStateManager.noteRateLimited()
                    _uiState.update { it.copy(deviceTransferError = e.message) }
                },
            )
        }
    }

    // ── Play track (keeps SDK fallback logic, always user-initiated) ──────────

    /**
     * Plays [uri] — alone, inside [contextUri] (positioned by `offset.uri`, honoured even with
     * shuffle ON), or at the head of [uris].
     *
     * There is no `index` any more (2026-09-25): the 404 fallback used to `skipToIndex` into the
     * context through the App Remote, which is fire-and-forget and was dispatched before a freshly
     * started Spotify had loaded the context, so it was DROPPED and song 1 played. The fallback now
     * plays the single item for instant audio and then sends the context / uris body ONCE through
     * the Web API, positioned by uri ([restoreAfterWake]).
     *
     * @param startPositionMs where the caller knows this item should start — an episode's
     *   `resume_point` (`ShowDetailScreen`), or null to let the API decide. It is deliberately NOT
     *   forwarded to `me/player/play`: the server resumes an episode from its own authoritative
     *   position, which a cached page's resume point can be stale against. It is used ONLY on the
     *   App Remote fallback, whose `play(uri)` always starts at 0:00 and has no server point to
     *   consult. **Live** since `user-read-playback-position` joined `SpotifyAuthManager.SCOPES`
     *   (2026-09-15), which is what populates `resume_point`; it still arrives null on a session
     *   authorized before that scope existed (a refresh never widens a grant), and on an episode
     *   the user has never started — in both cases the REST path's server-side resume, unchanged
     *   here, is what places the playhead.
     * @param shuffle When non-null, the shuffle mode is set to it BEFORE the play request. iLyra
     *   passes `false` for a deliberate song selection. When null and the body is a multi-uri
     *   `uris` list while the mirror says shuffle is ON, the play is BRACKETED instead: shuffle OFF
     *   → play → shuffle ON again ~1.5 s later — a `uris` body with shuffle on starts at a RANDOM
     *   entry (the Liked Songs "tapped one song, got another" bug), while the bracket gives what a
     *   playlist tap gives natively: the tapped song first, the rest shuffled. Context bodies are
     *   never bracketed (their offset is honoured).
     */
    fun playTrack(
        uri            : String,
        contextUri     : String?       = null,
        uris           : List<String>? = null,
        startPositionMs: Long?         = null,
        shuffle        : Boolean?      = null,
    ) = startPlay(
        uri             = uri,
        contextUri      = contextUri,
        uris            = uris,
        startPositionMs = startPositionMs,
        shuffle         = shuffle,
        origin          = when {
            contextUri != null -> PlaybackOrigin.forContext(contextUri)
            else               -> PlaybackOrigin.forUris(uris ?: listOf(uri))
        },
    )

    private fun startPlay(
        uri            : String,
        contextUri     : String?,
        uris           : List<String>?,
        startPositionMs: Long?,
        shuffle        : Boolean?,
        origin         : PlaybackOrigin,
    ) {
        val isEpisode = uri.startsWith("spotify:episode:")
        // Recorded BEFORE anything is sent, and it bumps the play-request generation: an older
        // wake restore still waiting for its device sees the bump and abandons silently.
        playerStateManager.recordPlayOrigin(origin)
        val generation = playerStateManager.playGeneration
        playerStateManager.setOptimisticallyPlaying()
        playerStateManager.resetProgressForNewTrack()
        _uiState.update { it.copy(isPlaying = true, isLiked = false, error = null, isWakingUp = true) }
        // `isLiked` was just cleared above, which is already the right answer for an episode —
        // and checkIsLiked would otherwise ask me/tracks/contains about `spotify:track:<episode
        // id>`, i.e. about a different item entirely. me/player/play takes episode uris in `uris`
        // (the `uri` branch of repository.play), so nothing else here needs to change.
        if (!isEpisode) {
            val trackId = uri.substringAfterLast(":")
            viewModelScope.launch { checkIsLiked(trackId) }
        }
        playbackJob?.cancel()
        val owner = ++wakingOwner
        playbackJob = viewModelScope.launch {
            // The body an EPISODE degrades to, and the body it restores with — null for a track.
            // `repository.play` is wire-identical for `uris = [uri]` and `uri = uri` (both send
            // `PlayRequest(uris = listOf(uri))`), so this changes nothing about the request; what it
            // changes is that `playUris` stays non-null for an episode, so the wake path always
            // has a body to send. Without it a ONE-EPISODE show (whose `uris` is null by design)
            // was never restored at all, so the App Remote's 0:00 start was never corrected — the
            // Web API's play is the only one of the two that honours Spotify's server-side resume
            // point. Gated on `contextUri == null` so it can never pre-empt a context body.
            val episodeSingleUri = listOf(uri).takeIf { isEpisode && contextUri == null }
            // The uri list actually being sent. Queue continuity is BEST-EFFORT: `uris` holding
            // more than one entry is undocumented for episodes (the Web API reference describes
            // `uris` as track uris, and a show is not a valid `context_uri`), while a SINGLE uri is
            // the shape podcasts shipped on and tracks have always used. So the first time the API
            // refuses the multi-uri body we degrade to `[uri]` once and carry on — the user loses
            // the queue, not the playback. `playUris` is a var so the wake path below never
            // re-sends a body the API has already rejected.
            var playUris = uris ?: episodeSingleUri
            // BUG C (2026-09-25): see the `shuffle` KDoc. The user's setting is the mirror OR a
            // bracket still owed ON (a previous bracket's OFF has not been undone yet — see
            // PlayerStateManager.shuffleOwedOn). The mirror can be stale on a cold start (defaults
            // false), which only means the bracket is skipped, never misapplied.
            if (shuffle != null) playerStateManager.clearShuffleOwed()
            val owedOn = playerStateManager.shuffleOwedOn
            val userShuffleOn = playerStateManager.state.value.shuffleEnabled || owedOn
            val multiUriBody = contextUri == null && (playUris?.size ?: 0) > 1
            val bracketShuffle = shuffle == null && multiUriBody && userShuffleOn
            // Device pass 2026-09-25 (checklist A3): Spotify DROPPED shuffle when a playlist
            // context was started right after a `uris` playback, though it had kept it that
            // morning when the previous playback was itself a context. So whenever the user's
            // shuffle is on and the caller left it to us, shuffle is re-asserted ON after EVERY
            // successful play — the bracket's second half, now for context and single bodies too.
            // A context's offset is honoured with shuffle on, so the tapped song keeps playing and
            // only the rest is (re)shuffled; an ON that Spotify kept anyway is idempotent.
            val reassertOn = shuffle == null && userShuffleOn
            val effectiveShuffle = when {
                shuffle != null -> shuffle
                bracketShuffle  -> false
                // A context or single-item body with a debt outstanding: settle it BEFORE the play
                // (a context's offset is honoured with shuffle on; a single item cannot start wrong).
                owedOn          -> true
                else            -> null
            }
            if (reassertOn) playerStateManager.markShuffleOwedOn()
            // ── Shuffle pre-set ──────────────────────────────────────────────
            // When the caller says "turn shuffle OFF before playing" (iLyra deliberate selection,
            // or the bracket above) or ON, apply it before the play request so the uris body starts
            // at the tapped entry instead of a random one. Always sends the PUT when non-null: the
            // mirror may be stale (cold start, Spotify closed → shuffleEnabled defaults false while
            // the device is actually shuffling), so comparing against it would skip the PUT in
            // exactly the scenario the user hits first. One idempotent PUT per deliberate selection
            // is the correct price. A 404 is NOT an error — it means "no active device", and the
            // App Remote fallback below handles both the shuffle and the play.
            if (effectiveShuffle != null) {
                val err = playerStateManager.applyShuffle(effectiveShuffle).exceptionOrNull()
                if (err != null && err.isRateLimited()) playerStateManager.noteRateLimited()
                // Only an OFF before a multi-uri body can start the wrong song; an ON before a
                // context / single body cannot, so it is not worth a round trip.
                if (err == null && !effectiveShuffle && multiUriBody) confirmShuffleState(false)
            }
            var result = repository.play(
                uri        = uri,
                contextUri = contextUri,
                offsetUri  = if (contextUri != null) uri else null,
                uris       = playUris,
            )
            val firstError = result.exceptionOrNull()
            if (firstError != null && (playUris?.size ?: 0) > 1 && firstError.isRequestRefused()) {
                Log.w(TAG, "play() refused a ${playUris?.size}-uri body (${firstError.message}); " +
                           "retrying with the single uri", firstError)
                // `episodeSingleUri`, not a bare null: the multi-uri body is what was refused, and
                // the single uri is the proven shape — so an episode keeps a body to restore with
                // (and its resume point) instead of being stranded at the SDK's 0:00. A track has
                // nothing to restore once the queue is gone, and gets null as before.
                playUris = episodeSingleUri
                result = repository.play(
                    uri        = uri,
                    contextUri = contextUri,
                    offsetUri  = if (contextUri != null) uri else null,
                    uris       = null,
                )
                val retryError = result.exceptionOrNull()
                if (retryError != null) {
                    Log.w(TAG, "single-uri retry also failed", retryError)
                    // Noted HERE as well as in onFailure below: when the retry is rate limited the
                    // original rejection is what gets surfaced (next line), so the 429 would never
                    // reach the failure branch and the backoff gate would never be armed.
                    if (retryError.isRateLimited()) playerStateManager.noteRateLimited()
                    // A 404 on the retry means "no active device", which the App Remote fallback
                    // below can still fix — keep it so that path runs. Anything else: surface the
                    // ORIGINAL rejection, which names the real cause rather than its second symptom.
                    if (!retryError.isNoActiveDevice()) result = Result.failure(firstError)
                }
            }
            result.fold(
                onSuccess = {
                    if (reassertOn) reassertShuffleOn(owner)
                    delay(1_000L)
                    playerStateManager.fetchOnce()
                    clearIsWakingUp()
                },
                onFailure = { e ->
                    if (e.isNoActiveDevice()) {
                        val body = when {
                            contextUri != null -> WakeRestoreBody.Context(contextUri)
                            playUris   != null -> WakeRestoreBody.Uris(playUris)
                            // A track whose multi-uri body was refused above: nothing to rebuild.
                            else               -> null
                        }
                        restoreAfterWake(
                            uri             = uri,
                            body            = body,
                            positionMs      = if (isEpisode) null else 0L,
                            shuffle         = effectiveShuffle,
                            startPositionMs = startPositionMs,
                            reassertShuffle = reassertOn,
                            generation      = generation,
                            owner           = owner,
                            degradeTo       = episodeSingleUri,
                        )
                    } else {
                        // Share the one 60-second penalty window with every other caller rather
                        // than letting this path fire again into an active limit (docs/SPOTIFY.md →
                        // Rate Limiting); the device-transfer and volume paths do the same.
                        if (e.isRateLimited()) playerStateManager.noteRateLimited()
                        // The bracket turned the user's shuffle OFF; a failed play must not leave
                        // it that way. (Not inside a rate limit — that PUT would only be refused.)
                        else if (reassertOn) restoreShuffleOn(owner)
                        playerStateManager.releasePlayingOptimism()
                        _uiState.update { it.copy(error = e.message, isPlaying = false) }
                        clearIsWakingUp()
                    }
                },
            )
        }
    }

    /**
     * The ONE App Remote wake path — a track tap whose `me/player/play` answered 404 (no active
     * device) and the play button after Spotify died while paused both end here
     * (docs/PLAYER.md → Playback 404 Fallback).
     *
     *  1. The caller has set the waking state and the optimistic playing lock; both are held until
     *     step 5 — Cris's rule: the player shows WAKING until Spotify REPORTS the song playing,
     *     never cleared on a timer or on an IPC dispatch.
     *  2. [shuffle] (when non-null) goes through the App Remote first, with its settle delay.
     *  3. The SDK plays the single item for instant audio (and seeks to [startPositionMs] after
     *     [SDK_SEEK_SETTLE_MS] when known). `connectSuspend` resumes only after Spotify's
     *     `onConnected`, so a cold Spotify's boot is spent before this returns.
     *  4. `me/player/devices` is polled every [WAKE_DEVICE_POLL_MS] until THIS phone is listed
     *     ([pickLocalDevice]) — up to [WAKE_DEVICE_TIMEOUT_MS] — and [body] is sent ONCE with its
     *     `device_id`: with it the device only has to be listed, not active. A 404 there is
     *     "listed but not ready" and goes back to polling; a 429 abandons; a refused multi-uri
     *     body degrades once to [degradeTo]. At the ceiling, ONE attempt without `device_id`.
     *  5. `me/player` is re-read every [WAKE_CONFIRM_POLL_MS] (up to [WAKE_CONFIRM_TIMEOUT_MS])
     *     until it reports [uri] PLAYING; then — or on a definitive failure, or at that ceiling
     *     (logged "unconfirmed": a relinked track can legitimately report a different uri) — the
     *     waking state clears.
     *
     * Superseded — the play-request [generation] moved because the user played, paused or skipped
     * something else meanwhile — it stops at the next checkpoint: no body, no error, no shuffle
     * change, and the waking state is cleared only if this restore still [owner]s it (see
     * [wakingOwner]); a newer play here keeps its own.
     *
     * @param positionMs the BASE position of a track body (0 for a fresh tap, the paused
     *   progress for the play button); the time since the SDK play is added when the body is
     *   sent, so the restore continues from where the SDK already is. Null for an episode.
     */
    private suspend fun restoreAfterWake(
        uri            : String,
        body           : WakeRestoreBody?,
        positionMs     : Long?,
        shuffle        : Boolean?,
        startPositionMs: Long?,
        reassertShuffle: Boolean,
        generation     : Long,
        owner          : Long,
        degradeTo      : List<String>?,
    ) {
        fun superseded() = playerStateManager.playGeneration != generation
        fun abandon(stage: String) {
            Log.d(TAG, "wake: superseded $stage — stopping")
            if (wakingOwner == owner) {
                clearIsWakingUp()
                // Superseded by a pause / skip / Library play, which do not settle a bracket: undo
                // it now. (A newer play here took the ownership and settles the debt itself.)
                if (reassertShuffle) restoreShuffleOn(owner)
            }
        }
        if (superseded()) { abandon("before it started"); return }
        ownedWakeRestores++
        try {
            // Cancel the connecting collector's 3.5 s fallback timer — this path owns the clear.
            clearWakingUpJob?.cancel()
            _uiState.update { it.copy(isWakingUp = true) }
            // Connect first, on its own, so a request superseded DURING the connect (a cold Spotify
            // can take ~30 s to answer) never dispatches its now-stale shuffle or play over the
            // newer one. setShuffle / play below then reuse the live bind.
            val sdkOk = remoteManager.connectSuspend()
            clearWakingUpJob?.cancel()
            if (superseded()) { abandon("during the App Remote connect"); return }
            if (!sdkOk) {
                Log.d(TAG, "wake: outcome failed — App Remote could not connect")
                playerStateManager.releasePlayingOptimism()
                _uiState.update { it.copy(error = "Couldn't connect to Spotify.", isPlaying = false) }
                clearIsWakingUp()
                return
            }
            if (shuffle != null) {
                remoteManager.setShuffle(shuffle)
                if (shuffle) playerStateManager.clearShuffleOwed()
                delay(REMOTE_SHUFFLE_SETTLE_MS)
            }
            remoteManager.play(uri)
            Log.d(TAG, "wake: SDK play dispatched ($uri)")
            // The SDK's play() starts an episode at 0:00 — unlike the Web API it does not consult
            // the server-side resume point — and the play button's track at 0:00 too. When the
            // caller knows the position, seek to it. The settle delay is required, not defensive:
            // connectAndPlay returns when the IPC call has been DISPATCHED, not when playback has
            // started, so a seek in the same breath arrives before the item does and is dropped.
            if (startPositionMs != null && startPositionMs > 0L) {
                delay(SDK_SEEK_SETTLE_MS)
                remoteManager.seekTo(startPositionMs)
            }
            // Captured AFTER the seek: from here on the SDK is at `positionMs + elapsed`.
            val sdkStartedAt = System.currentTimeMillis()

            val hints = deviceNameHints()
            var pending = body
            var accepted = false
            var abandoned = false
            var failed = false
            var bodyFailures = 0
            var listedLogged = false
            val deadline = sdkStartedAt + WAKE_DEVICE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (superseded()) { abandon("while waiting for the device"); return }
                // The optimistic lock is 5 s; the wait can be a minute. Without re-arming it, 204
                // polls flip isPlaying false under the notification, widget and visualizer gate.
                playerStateManager.lockIsPlaying()
                if (playerStateManager.isRateLimited()) {
                    Log.d(TAG, "wake: rate limited while waiting for the device; abandoning the restore")
                    abandoned = true
                    break
                }
                val devices = repository.getAvailableDevices()
                currentCoroutineContext().ensureActive()
                val devicesError = devices.exceptionOrNull()
                if (devicesError != null && devicesError.isRateLimited()) {
                    playerStateManager.noteRateLimited()
                    Log.d(TAG, "wake: me/player/devices rate limited; abandoning the restore")
                    abandoned = true
                    break
                }
                val device = devices.getOrNull()?.let { pickLocalDevice(it, hints) }
                val deviceId = device?.id
                if (deviceId != null) {
                    if (!listedLogged) {
                        listedLogged = true
                        Log.d(TAG, "wake: device listed after ${System.currentTimeMillis() - sdkStartedAt} ms " +
                                   "(${device.name} / $deviceId, active=${device.isActive})")
                    }
                    val toSend = pending
                    if (toSend == null) {
                        // Nothing to rebuild — the SDK play IS the playback. Go and confirm it.
                        accepted = true
                        break
                    }
                    val sent = sendWakeBody(toSend, uri, positionMs, sdkStartedAt, deviceId)
                    currentCoroutineContext().ensureActive()
                    val err = sent.exceptionOrNull()
                    Log.d(TAG, "wake: body sent ${toSend.describe()} to $deviceId → " +
                               (err?.message ?: "accepted"))
                    when {
                        err == null -> { accepted = true; break }
                        err.isRateLimited() -> {
                            // A 429 says nothing about the body; the same body is fine once the
                            // window passes. Arm the shared gate and stop — re-sending inside a
                            // penalty window is the hammering the gate exists to stop. The SDK is
                            // still playing the single item.
                            playerStateManager.noteRateLimited()
                            abandoned = true
                            break
                        }
                        // "Listed but not ready" — exactly what this loop waits out.
                        err.isNoActiveDevice() -> Unit
                        // A multi-uri body the API REFUSES is never re-sent: an episode falls back
                        // to the single uri (one more send can still place it at its resume point),
                        // a track to nothing (the SDK is playing it).
                        toSend is WakeRestoreBody.Uris && toSend.uris.size > 1 && err.isRequestRefused() -> {
                            pending = degradeTo?.let { WakeRestoreBody.Uris(it) }
                            continue
                        }
                        // Anything else (a 5xx, a restriction on a context body): a few retries,
                        // then give up — the SDK is playing the single item either way.
                        else -> if (++bodyFailures >= WAKE_MAX_BODY_FAILURES) {
                            Log.d(TAG, "wake: body failed $bodyFailures times; giving up the restore")
                            failed = true
                            break
                        }
                    }
                }
                delay(WAKE_DEVICE_POLL_MS)
            }
            if (!accepted && !abandoned && !failed) {
                if (superseded()) { abandon("at the device ceiling"); return }
                val toSend = pending
                if (toSend == null) {
                    accepted = true
                } else if (!playerStateManager.isRateLimited()) {
                    // Today's behaviour, once: no device_id, whichever device is active.
                    val sent = sendWakeBody(toSend, uri, positionMs, sdkStartedAt, deviceId = null)
                    currentCoroutineContext().ensureActive()
                    val err = sent.exceptionOrNull()
                    if (err != null && err.isRateLimited()) playerStateManager.noteRateLimited()
                    Log.d(TAG, "wake: device never listed in ${WAKE_DEVICE_TIMEOUT_MS} ms; one body " +
                               "${toSend.describe()} without device_id → ${err?.message ?: "accepted"}")
                    accepted = err == null
                }
            }

            var confirmed = false
            if (accepted) {
                val confirmDeadline = System.currentTimeMillis() + WAKE_CONFIRM_TIMEOUT_MS
                while (System.currentTimeMillis() < confirmDeadline) {
                    delay(WAKE_CONFIRM_POLL_MS)
                    if (superseded()) { abandon("while confirming"); return }
                    if (playerStateManager.isRateLimited()) break
                    playerStateManager.lockIsPlaying()
                    val observed = playerStateManager.fetchOnce()
                    if (observed != null && observed.isPlaying && observed.item?.uri == uri) {
                        confirmed = true
                        break
                    }
                }
            } else {
                // Definitive: the body never landed. Sync the mirror with whatever the SDK has.
                playerStateManager.fetchOnce()
            }
            if (superseded()) { abandon("at the outcome"); return }
            Log.d(TAG, "wake: outcome " + when {
                confirmed -> "confirmed — $uri playing after ${System.currentTimeMillis() - sdkStartedAt} ms"
                accepted  -> "unconfirmed — body accepted, $uri not reported playing within ${WAKE_CONFIRM_TIMEOUT_MS} ms"
                abandoned -> "abandoned (rate limited) — the SDK plays the single item"
                else      -> "failed — the SDK plays the single item"
            })
            clearIsWakingUp()
            // A 429 abandon leaves the debt owed (shuffleOwedOn): a PUT now would only be refused,
            // and the next play settles it.
            if (reassertShuffle) {
                if (accepted) reassertShuffleOn(owner) else if (!abandoned) restoreShuffleOn(owner)
            }
        } finally {
            ownedWakeRestores--
        }
    }

    /** One `me/player/play` for [body], targeted at [deviceId] (null = the active device). */
    private suspend fun sendWakeBody(
        body        : WakeRestoreBody,
        uri         : String,
        positionMs  : Long?,
        sdkStartedAt: Long,
        deviceId    : String?,
    ): Result<Unit> {
        val elapsedMs = System.currentTimeMillis() - sdkStartedAt
        // An EPISODE resumes from Spotify's own server-side position when the play call carries NO
        // `position_ms` — which is why a half-listened episode resumes correctly whenever the Web
        // API is the path that starts it. Sending `elapsedMs` overrode that and pinned the restored
        // episode a second or two from the START: the symptom Cris hit was "force-stop Spotify, tap
        // a half-listened episode, it plays from 0:00". A track has no resume point, so for tracks
        // `base + elapsedMs` is exactly what makes the restore seamless.
        //
        // Read off the body's OWN first entry as well as the caller's null: `position_ms` applies
        // to whatever `uris` starts with, so this stays right even if a caller ever leads with
        // something other than the tapped item.
        return when (body) {
            is WakeRestoreBody.Context -> repository.play(
                contextUri = body.contextUri,
                offsetUri  = uri,
                positionMs = positionMs?.plus(elapsedMs),
                deviceId   = deviceId,
            )
            is WakeRestoreBody.Uris -> {
                val leadIsEpisode = body.uris.firstOrNull()?.startsWith("spotify:episode:") == true
                repository.play(
                    uris       = body.uris,
                    positionMs = if (leadIsEpisode) null else positionMs?.plus(elapsedMs),
                    deviceId   = deviceId,
                )
            }
        }
    }

    private fun WakeRestoreBody.describe(): String = when (this) {
        is WakeRestoreBody.Context -> "context ${contextUri}"
        is WakeRestoreBody.Uris    -> "uris ×${uris.size}"
    }

    /**
     * The second half of the shuffle bracket: turn shuffle back ON ~1.5 s after a successful play
     * — the `shuffleContext` re-assert pattern. Spotify keeps the playing item and shuffles the
     * rest. Gated on OWNERSHIP ([wakingOwner]), not the play-request generation: a newer play
     * here inherits the debt (`shuffleOwedOn`) and settles it itself, while a pause, skip or
     * Library play in between does not, so the re-assert must still run for those. Skipped when
     * the debt is already gone (the user chose a shuffle state) and inside the rate-limit window.
     */
    private fun reassertShuffleOn(owner: Long) {
        viewModelScope.launch {
            delay(SHUFFLE_REASSERT_DELAY_MS)
            settleShuffleDebt(owner)
        }
    }

    /**
     * Waits until `me/player` reports `shuffle_state == [enabled]` (see [SHUFFLE_CONFIRM_POLL_MS]).
     * Rate-limit gated; a 204 (no device) or a failure ends the wait early — the play that follows
     * will 404 and the App Remote path applies shuffle itself.
     */
    private suspend fun confirmShuffleState(enabled: Boolean) {
        val deadline = System.currentTimeMillis() + SHUFFLE_CONFIRM_TIMEOUT_MS
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            if (playerStateManager.isRateLimited()) return
            val observed = playerStateManager.fetchOnce() ?: return
            polls++
            if (observed.shuffleState == enabled) {
                Log.d(TAG, "shuffle: ${if (enabled) "ON" else "OFF"} confirmed after $polls poll(s)")
                return
            }
            delay(SHUFFLE_CONFIRM_POLL_MS)
        }
        Log.d(TAG, "shuffle: ${if (enabled) "ON" else "OFF"} NOT confirmed within ${SHUFFLE_CONFIRM_TIMEOUT_MS} ms; playing anyway")
    }

    /** The bracket's undo when the play failed or was superseded: the user's shuffle comes back. */
    private fun restoreShuffleOn(owner: Long) {
        viewModelScope.launch { settleShuffleDebt(owner) }
    }

    private suspend fun settleShuffleDebt(owner: Long) {
        if (wakingOwner != owner || !playerStateManager.shuffleOwedOn) return
        if (playerStateManager.isRateLimited()) return   // stays owed; the next play settles it
        Log.d(TAG, "shuffle: re-asserting ON after the play")
        playerStateManager.applyShuffle(true).onFailure { e ->
            when {
                e.isRateLimited()    -> playerStateManager.noteRateLimited()
                e.isNoActiveDevice() -> {
                    remoteManager.setShuffle(true)
                    playerStateManager.clearShuffleOwed()
                }
            }
        }
    }

    /**
     * The play button's half of [restoreAfterWake], installed as
     * `PlayerStateManager.onWakeRestore`. Runs in [viewModelScope] (it touches VM-owned state) as
     * the current [playbackJob] and is awaited by the manager's play coroutine. False only when
     * the ViewModel is already gone — the manager then falls back to the bare single-uri play.
     */
    private suspend fun runPlayButtonRestore(track: SpotifyTrack, pausedProgressMs: Long): Boolean {
        if (!viewModelScope.isActive) return false
        val job = withContext(Dispatchers.Main) {
            playbackJob?.cancel()
            val generation = playerStateManager.playGeneration
            val owner = ++wakingOwner
            viewModelScope.async { restoreForResume(track, pausedProgressMs, generation, owner) }
                .also { playbackJob = it }
        }
        return try {
            job.await()
            true
        } catch (e: CancellationException) {
            // Superseded by a newer play, or the ViewModel went away mid-restore: either way the
            // restore has stopped on purpose, and the manager must NOT start a second play. Only a
            // cancellation of the CALLER is rethrown.
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "wake: play-button restore cancelled (${e.message})")
            true
        }
    }

    private suspend fun restoreForResume(
        track           : SpotifyTrack,
        pausedProgressMs: Long,
        generation      : Long,
        owner           : Long,
    ) {
        val uri = track.uri
        val isEpisode = track.isEpisode
        val mirror = playerStateManager.state.value
        val ctx = mirror.contextUri
        val origin = playerStateManager.loadPlayOrigin()
        // The liked cache is the whole library file; read it only when the plan can use it.
        val planUsesLiked = !isEpisode && (ctx == null || isCollectionContext(ctx)) &&
            (isCollectionContext(ctx) || origin is PlaybackOrigin.Liked || origin == null)
        val likedUris = if (!planUsesLiked) null else withContext(Dispatchers.IO) {
            libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks
                ?.distinctBy { it.id }?.map { it.uri }
        }
        val body = planWakeRestore(uri, ctx, origin, likedUris, isEpisode)
        // The same bracket as a tap: a multi-uri body with shuffle on would start at random. The
        // user's setting is the mirror OR a previous bracket's debt (shuffleOwedOn).
        val owedOn = playerStateManager.shuffleOwedOn
        val userShuffleOn = mirror.shuffleEnabled || owedOn
        val bracket = body is WakeRestoreBody.Uris && body.uris.size > 1 && userShuffleOn
        val sdkShuffle = when {
            bracket -> false
            owedOn  -> true    // a context / single body: settle the debt before the play
            else    -> null
        }
        // Re-asserted ON after the body lands whenever the user's shuffle is on (see startPlay:
        // Spotify can drop shuffle when a context starts), not only after a bracket's OFF.
        if (userShuffleOn) playerStateManager.markShuffleOwedOn()
        val originLabel = when (origin) {
            is PlaybackOrigin.Context -> "context ${origin.contextUri}"
            PlaybackOrigin.Liked      -> "liked"
            is PlaybackOrigin.Uris    -> "uris ×${origin.uris.size}"
            null                      -> "none"
        }
        Log.d(TAG, "wake: play button — plan ${body.describe()} (mirror context=$ctx, " +
                   "origin=$originLabel, liked cache=${likedUris?.size}, shuffleBracket=$bracket)")
        restoreAfterWake(
            uri             = uri,
            body            = body,
            positionMs      = if (isEpisode) null else pausedProgressMs,
            shuffle         = sdkShuffle,
            startPositionMs = pausedProgressMs,
            reassertShuffle = userShuffleOn,
            generation      = generation,
            owner           = owner,
            degradeTo       = if (isEpisode) listOf(uri) else null,
        )
    }

    /**
     * Play a context (playlist / album / the Liked `collection`) with shuffle ON — the hero Shuffle
     * button (Library, since 2026-09-25) and iLyra's Shuffle Songs. THE one shuffle-play path.
     *
     * Awake: shuffle ON is awaited AND CONFIRMED by re-reading `me/player` before the context play
     * goes out ("order of execution is not guaranteed" across player endpoints — the Liked hero
     * sometimes started in order, checklist B7), then the play, then one re-assert 1.5 s later.
     *
     * Cold (404): the App Remote applies shuffle, then plays the CONTEXT itself (proven: the whole
     * context loads — device pass 2026-09-25 B7 / IPOD #8). The player shows WAKING until Spotify
     * reports playing (Cris's rule), then shuffle is re-asserted through the Web API if the poll
     * says it did not stick. Before 2026-09-25 the Library's own `shufflePlaylist` never set
     * shuffle on the SDK at all, so a cold Shuffle tap played in order and needed a second tap.
     *
     * Rate-limit-gated throughout; a newer play cancels it (it is the current [playbackJob]).
     */
    fun shuffleContext(contextUri: String) {
        if (playerStateManager.isRateLimited()) return
        playerStateManager.recordPlayOrigin(PlaybackOrigin.forContext(contextUri))
        playerStateManager.clearShuffleOwed()   // an explicit shuffle-ON play settles a bracket's debt
        playerStateManager.setOptimisticallyPlaying()
        playbackJob?.cancel()
        val owner = ++wakingOwner
        val generation = playerStateManager.playGeneration
        playbackJob = viewModelScope.launch {
            val shuffleResult = playerStateManager.applyShuffle(true)
            val shuffleErr = shuffleResult.exceptionOrNull()
            if (shuffleErr != null && shuffleErr.isRateLimited()) {
                playerStateManager.noteRateLimited()
                playerStateManager.releasePlayingOptimism()
                return@launch
            }
            val shuffleWas404 = shuffleErr != null && shuffleErr.isNoActiveDevice()
            // The ON must have TAKEN EFFECT before the context play, or the play is applied first
            // and the context starts in order from track 1.
            if (shuffleErr == null) confirmShuffleState(true)

            repository.play(contextUri = contextUri).fold(
                onSuccess = {
                    // Re-assert: Spotify sometimes applies the play before the shuffle, so the
                    // first item is in-order. Clear the optimistic lock so fetchOnce reads the
                    // server's truth, then re-set shuffle if it didn't stick.
                    delay(SHUFFLE_REASSERT_DELAY_MS)
                    playerStateManager.clearShuffleLock()
                    playerStateManager.fetchOnce()
                    if (!playerStateManager.state.value.shuffleEnabled) {
                        Log.d(TAG, "shuffle: context play landed in order; re-asserting ON")
                        repository.setShuffle(true)
                            .onFailure { if (it.isRateLimited()) playerStateManager.noteRateLimited() }
                    }
                },
                onFailure = { e ->
                    if (e.isNoActiveDevice() || shuffleWas404) {
                        shuffleContextViaRemote(contextUri, owner, generation)
                    } else {
                        if (e.isRateLimited()) playerStateManager.noteRateLimited()
                        playerStateManager.releasePlayingOptimism()
                    }
                },
            )
        }
    }

    /**
     * The cold half of [shuffleContext]: connect, shuffle ON on the SDK, play the context on the
     * SDK, hold the waking state until `me/player` reports playing (up to [WAKE_CONFIRM_TIMEOUT_MS]
     * after the phone is listed, like a track restore), then re-assert shuffle if it did not stick.
     */
    private suspend fun shuffleContextViaRemote(contextUri: String, owner: Long, generation: Long) {
        fun superseded() = playerStateManager.playGeneration != generation
        ownedWakeRestores++
        try {
            clearWakingUpJob?.cancel()
            _uiState.update { it.copy(isWakingUp = true) }
            val ok = remoteManager.connectSuspend()
            clearWakingUpJob?.cancel()
            if (superseded()) return
            if (!ok) {
                // A failed bind must not leave a 5 s "playing" lock with nothing playing.
                Log.d(TAG, "wake: shuffle context — App Remote could not connect")
                playerStateManager.releasePlayingOptimism()
                _uiState.update { it.copy(error = "Couldn't connect to Spotify", isPlaying = false) }
                return
            }
            remoteManager.setShuffle(true)
            delay(REMOTE_SHUFFLE_SETTLE_MS)
            remoteManager.play(contextUri)
            val startedAt = System.currentTimeMillis()
            Log.d(TAG, "wake: SDK shuffle + context play dispatched ($contextUri)")
            // Confirm: the phone registers as a device a few seconds after the SDK play; the poll
            // answers 204 until then. Same cadence and rule as a track restore's confirm step.
            var observedShuffle: Boolean? = null
            val deadline = startedAt + WAKE_DEVICE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(WAKE_CONFIRM_POLL_MS)
                if (superseded()) return
                if (playerStateManager.isRateLimited()) break
                playerStateManager.lockIsPlaying()
                val observed = playerStateManager.fetchOnce() ?: continue
                if (observed.isPlaying) { observedShuffle = observed.shuffleState; break }
            }
            Log.d(TAG, "wake: shuffle context outcome " + when (observedShuffle) {
                null  -> "unconfirmed — not reported playing within ${WAKE_DEVICE_TIMEOUT_MS} ms"
                true  -> "confirmed shuffled after ${System.currentTimeMillis() - startedAt} ms"
                false -> "playing IN ORDER after ${System.currentTimeMillis() - startedAt} ms; re-asserting ON"
            })
            if (observedShuffle == false && !playerStateManager.isRateLimited()) {
                playerStateManager.clearShuffleLock()
                repository.setShuffle(true)
                    .onFailure { if (it.isRateLimited()) playerStateManager.noteRateLimited() }
            }
        } finally {
            ownedWakeRestores--
            if (wakingOwner == owner) clearIsWakingUp()
        }
    }
}

class PlayerViewModelFactory(private val container: com.crsmthw.lyra.di.AppContainer) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlayerViewModel(
            playerStateManager = container.playerStateManager,
            repository         = container.spotifyRepository,
            remoteManager      = container.remoteManager,
            libraryCache       = container.libraryCache,
            lyricsRepository   = container.lyricsRepository,
            settingsRepository = container.settingsRepository,
            visualizerManager  = container.visualizerManager,
            deviceNameHints    = container::localDeviceNameHints,
        ) as T
}
