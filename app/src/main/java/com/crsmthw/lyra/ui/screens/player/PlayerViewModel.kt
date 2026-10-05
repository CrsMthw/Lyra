package com.crsmthw.lyra.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.ConfirmVerdict
import com.crsmthw.lyra.data.player.OTHER_ITEM_PLAYING_STREAK
import com.crsmthw.lyra.data.player.isOtherItemPlaying
import com.crsmthw.lyra.data.player.wakeConfirmReached
import com.crsmthw.lyra.data.player.PlayBody
import com.crsmthw.lyra.data.player.PlaybackOrigin
import com.crsmthw.lyra.data.player.PollObservation
import com.crsmthw.lyra.data.player.albumContextUri
import com.crsmthw.lyra.data.player.confirmVerdict
import com.crsmthw.lyra.data.player.fallbackBodies
import com.crsmthw.lyra.data.player.isNamedLikeThisPhone
import com.crsmthw.lyra.data.player.soleHandheld
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.player.WakeRestoreBody
import com.crsmthw.lyra.data.player.RateLimitFamily
import com.crsmthw.lyra.data.player.isCollectionContext
import com.crsmthw.lyra.data.player.likedCollectionUri
import com.crsmthw.lyra.data.player.pickLocalDevice
import com.crsmthw.lyra.data.player.planWakeRestore
import com.crsmthw.lyra.data.player.SdkAudibleWatch
import com.crsmthw.lyra.data.player.webViewBlind
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.httpStatus
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.crsmthw.lyra.data.remote.model.describe
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.remote.model.trackIdOf
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

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
 * SDK play — then NO body (2026-10-04). Two seconds keeps a minute's wait at ~30 calls.
 */
private const val WAKE_DEVICE_POLL_MS    = 2_000L
private const val WAKE_DEVICE_TIMEOUT_MS = 60_000L

/**
 * How long the phone may stay unlisted during the device wait before the player shows the quiet
 * "Spotify isn't visible to Lyra yet — Open Spotify" hint (2026-10-04: an idle background Spotify
 * drops off Connect, and an App Remote play does not re-register it; opening its UI does).
 */
private const val WAKE_NOT_LISTED_HINT_MS = 15_000L
/** How long a Connect dropout must hold before the player shows the "Open Spotify" line for it. */
private const val CONNECT_DROPOUT_HINT_MS = 15_000L

/**
 * After the body is accepted, `me/player` every [WAKE_CONFIRM_POLL_MS] until Spotify REPORTS the
 * song playing (the only thing that clears the waking state), for at most [WAKE_CONFIRM_TIMEOUT_MS]
 * — 25 s since 2026-10-04, room for an EMPTY player's fallback bodies.
 */
private const val WAKE_CONFIRM_POLL_MS    = 1_000L
private const val WAKE_CONFIRM_TIMEOUT_MS = 25_000L

/**
 * An awake play's confirm (2026-10-04, `awaitReportedPlaying`): `me/player` every
 * [PLAY_CONFIRM_POLL_MS] until Spotify REPORTS the song playing, for at most
 * [PLAY_CONFIRM_CEILING_MS] — then the player shows NOT playing and "Spotify didn't report the song
 * playing." Spotify Android 9.1.88 accepts a bare `uris` body and stops; before this the spinner
 * cleared one second after the PUT whatever the GET said.
 */
private const val PLAY_CONFIRM_POLL_MS    = 1_000L
private const val PLAY_CONFIRM_CEILING_MS = 25_000L

/** A fallback body or the App Remote rescue gets at least this long to be reported (review 2026-10-04). */
private const val PLAY_CONFIRM_RESCUE_MS  = 10_000L

/**
 * EMPTY polls in a row before a play of the caller's OWN context (a playlist / album row) falls
 * back: such a body starts at once on 9.1.88, and two polls (~2 s) cost a slow start its context.
 */
private const val CALLER_CONTEXT_EMPTY_STREAK = 5

/**
 * 2026-10-04 evening — ON (Cris approved it with the SDK mirror): the App Remote's own player state
 * counts as "Spotify reports the song playing" — two local snapshots after the dispatch, not paused,
 * on the item, ≥ 500 ms apart with the position advanced ≥ 500 ms (`sdkReportsAudible`, the
 * [SdkAudibleWatch]):
 *  - in the wake restore's DEVICE WAIT — the spinner clears over audible music while the phone is
 *    still unlisted (A3); the wait carries on behind it at [WAKE_DEVICE_POLL_AFTER_SDK_MS] until the
 *    phone is listed (the body then goes out with `position_ms` = base + elapsed, no audible jump)
 *    or the 60 s ceiling, where it stops silently; the "Open Spotify" hint logic is unchanged;
 *  - in an awake play's confirm ([awaitReportedPlaying]) and the play button's SDK resume — only
 *    while `me/player` is BLIND (`webViewBlind`: a 204, or an item-less 200 on this phone).
 * false = the 2026-10-04 afternoon behaviour: only the Web API confirms.
 */
private const val SDK_REPORT_CONFIRMS_PLAY = true

/**
 * The device wait's cadence once the local app has been taken as playing (the spinner is gone): the
 * wait only has to catch the phone being listed, so it slows from [WAKE_DEVICE_POLL_MS] to this.
 * Slept in [WAKE_SUPERSEDE_CHECK_MS] slices so a pause / tap is noticed within a second.
 */
private const val WAKE_DEVICE_POLL_AFTER_SDK_MS = 5_000L
private const val WAKE_SUPERSEDE_CHECK_MS        = 1_000L

/**
 * 2026-10-04 evening — the Liked COLLECTION shuffle bracket's switch. The bracket (shuffle OFF
 * confirmed → collection + `offset.uri` → shuffle ON re-asserted) rests on ONE evening only: on
 * 2026-09-25 every collection failure was collection + shuffle ON, with a library-family ban active
 * and the mid-September Spotify regression (A1) already rolling out — evidence the register (A7)
 * distrusts. true (default) = bracketed. false = a Liked tap (and a collection wake restore) with
 * shuffle ON sends the collection + offset with NO OFF/ON bracket; the post-play re-assert
 * (`reassertOn`, A4) is unchanged. Retest (register B1 / A7): shuffle ON, five Liked taps with this
 * false — every one must log `play: confirmed … via context spotify:user:…:collection` on the TAPPED
 * song; then the bracket can go.
 */
private const val COLLECTION_BRACKET_ENABLED = true

/**
 * After a device-picker transfer, `me/player` is read for this long; a transfer to THIS phone that
 * Spotify reports EMPTY (Android 9.1.88) is redone through the App Remote, as "This device" does.
 */
private const val TRANSFER_CONFIRM_MS = 4_000L

/** The transfer's App Remote fallback then waits this long for `me/player` to report it (review 2026-10-04). */
private const val TRANSFER_SDK_CONFIRM_MS = 5_000L

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
private const val SHUFFLE_CONFIRM_POLL_MS    = 500L
private const val SHUFFLE_CONFIRM_TIMEOUT_MS = 1_500L

/** How many albums' cassette fine print the player keeps (one small entry per album id). */
private const val CASSETTE_META_CACHE_SIZE = 24

/**
 * "No active device" — `me/player/play` answers 404 when Spotify is not running anywhere. It is the
 * trigger for the App Remote fallback, NOT a rejection of the request body, so nothing may degrade
 * on it. Read off the TYPED status (`SpotifyHttpException.code`, audit 2026-10-04 W14) — the old
 * substring test of the message also matched a Retry-After value or a port number in a timeout.
 */
private fun Throwable.isNoActiveDevice(): Boolean = httpStatus() == 404

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
    val status = httpStatus() ?: return false
    return status in 400..499 && status !in setOf(401, 404, 429)
}

/**
 * Spotify's rate limit (429), read off the typed status (docs/SPOTIFY.md → Rate Limiting): every
 * caller that sees one must call `PlayerStateManager.noteRateLimited()` so the whole app shares the
 * one penalty window instead of hammering independently.
 */
private fun Throwable.isRateLimited(): Boolean = httpStatus() == 429

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
    /**
     * A play Lyra issued was ACCEPTED but Spotify never reported it playing within the confirm
     * ceiling (2026-10-04). The player shows NOT playing plus one quiet line; the next poll that
     * reports an item clears it, as does the next play.
     */
    val playUnconfirmed       : Boolean        = false,
    /**
     * The wake restore waited [WAKE_NOT_LISTED_HINT_MS] and this phone is still not listed by
     * `me/player/devices` — the player offers "Open Spotify" (opening its UI re-registers it).
     * Cleared when a poll reports a device [pickLocalDevice] takes for this phone.
     */
    val spotifyNotListed      : Boolean        = false,
    /**
     * Spotify has dropped off Connect while the local app plays (`PlayerStateManager.connectDropout`,
     * latched, held for [CONNECT_DROPOUT_HINT_MS] first so a healthy cold wake — SDK audio ~2 s before
     * the phone is listed — never flashes it). The player shows the same "Open Spotify" line as
     * [spotifyNotListed]; cleared when the Web API source returns (Cris, device pass 2026-10-04 23:2x:
     * the hint appeared only after a second tap went through the wake path).
     */
    val connectDropout        : Boolean        = false,
) {
    val progress: Float
        get() = if (durationMs > 0L) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** The "Spotify isn't visible to Lyra" line with its Open Spotify button — either reason. */
    val showsOpenSpotifyHint: Boolean
        get() = spotifyNotListed || connectDropout
}

@OptIn(FlowPreview::class)
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
    /** The playing item's URI, for row highlights — never the id: two local files share `null` (audit W4). */
    val currentTrackUri: StateFlow<String?> = _uiState.map { it.currentTrack?.uri }.distinctUntilChanged()
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

    /** Installed as `PlayerStateManager.isLocalDevice`; kept so [onCleared] removes only its own. */
    private val localDeviceHook: (SpotifyDevice) -> Boolean = { device -> isThisPhone(device) }

    /** Installed as `PlayerStateManager.onWakeRestore`; kept so [onCleared] removes only its own. */
    private val wakeRestoreHook: suspend (SpotifyTrack, Long, Int) -> Boolean =
        { track, pausedProgressMs, step -> runPlayButtonRestore(track, pausedProgressMs, step) }

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
                // A change is a change of URI, never of id (audit 2026-10-04 W3): two local files
                // both carry `id == null`, so an id test missed every local → local change.
                if (newTrack != null && newTrack.uri != prevTrack?.uri) {
                    // A podcast episode is not a track: `me/tracks/contains` with an episode id
                    // answers about a DIFFERENT (or non-existent) track, so the heart would show
                    // someone else's saved state. The like affordance is hidden for episodes and
                    // for local files (nothing to ask about); this keeps the flag honest behind it.
                    val likeId = newTrack.likeTargetId
                    if (likeId == null) _uiState.update { it.copy(isLiked = false) }
                    else checkIsLiked(likeId)
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
            // Never over a VM-owned play: a restore or a confirm still running here holds its own
            // spinner and clears it when ITS song is reported (an SDK resume superseded by a tap).
            viewModelScope.launch { if (ownedWakeRestores == 0) clearIsWakingUp() }
        }
        // Every SUCCESSFUL poll (a SharedFlow: an equal observation is delivered too): an item
        // clears the "didn't report" line, and this phone reported — or anything playing — clears
        // the "Open Spotify" hint.
        viewModelScope.launch {
            playerStateManager.lastPoll.collect { obs -> onPollObserved(obs) }
        }
        // A Connect dropout held CONNECT_DROPOUT_HINT_MS shows the "Open Spotify" line; its end clears
        // it at once (debounce with a selector: the delay applies to true only).
        viewModelScope.launch {
            playerStateManager.connectDropout
                .debounce { on -> if (on) CONNECT_DROPOUT_HINT_MS else 0L }
                .distinctUntilChanged()
                .collect { on -> _uiState.update { if (it.connectDropout == on) it else it.copy(connectDropout = on) } }
        }
        // The play button's SDK resume over an EMPTY player only when that player is this phone's.
        playerStateManager.isLocalDevice = localDeviceHook
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
                            if (e.isRateLimited()) playerStateManager.noteRateLimited(e)
                            Log.w(TAG, "cassette album meta for $albumId failed: ${e.message}")
                        },
                    )
                }
        }
        remoteManager.connect(onConnected = { }, onFailure = { })
    }

    private fun onPollObserved(obs: PollObservation) {
        val ui = _uiState.value
        if (ui.playUnconfirmed && obs.itemUri != null) _uiState.update { it.copy(playUnconfirmed = false) }
        // Anything playing anywhere makes "Spotify isn't visible to Lyra" moot (review 2026-10-04).
        if (ui.spotifyNotListed && obs.status == 200 && obs.itemUri != null && obs.isPlaying) {
            Log.d(TAG, "poll: ${obs.itemUri} reported playing — Open Spotify hint cleared")
            _uiState.update { it.copy(spotifyNotListed = false) }
            return
        }
        if (ui.spotifyNotListed && obs.status == 200 && obs.deviceId != null) {
            val device = playerStateManager.state.value.currentDevice
            if (device != null && device.id == obs.deviceId &&
                pickLocalDevice(listOf(device), deviceNameHints()) != null) {
                Log.d(TAG, "poll: this phone is reported (${device.describe()}) — hint cleared")
                _uiState.update { it.copy(spotifyNotListed = false) }
            }
        }
    }

    override fun onCleared() {
        // The manager is app-scoped: a dead ViewModel's hook would leak it and restore into it.
        if (playerStateManager.onWakeRestore === wakeRestoreHook) playerStateManager.onWakeRestore = null
        if (playerStateManager.isLocalDevice === localDeviceHook) playerStateManager.isLocalDevice = null
    }

    private suspend fun checkIsLiked(trackId: String) {
        // `me/tracks/contains` is a LIBRARY-family call: skipped while that family is banned.
        if (playerStateManager.isRateLimited(RateLimitFamily.LIBRARY)) return
        repository.isTrackSaved(trackId).fold(
            onSuccess = { liked -> _uiState.update { it.copy(isLiked = liked) } },
            onFailure = { e -> if (e.isRateLimited()) playerStateManager.noteRateLimited(e, "me/tracks/contains", RateLimitFamily.LIBRARY) },
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
        val id = track.likeTargetId ?: return   // an episode or a local file: nothing to ask about
        viewModelScope.launch { checkIsLiked(id) }
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
                trackUri   = track.uri,
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

    fun playPause() {
        // A new request: the "didn't report" line belongs to the play before it.
        if (_uiState.value.playUnconfirmed) _uiState.update { it.copy(playUnconfirmed = false) }
        playerStateManager.playPause()
    }
    fun skipNext() {
        if (_uiState.value.playUnconfirmed) _uiState.update { it.copy(playUnconfirmed = false) }
        playerStateManager.skipNext()
    }
    fun skipPrevious() {
        if (_uiState.value.playUnconfirmed) _uiState.update { it.copy(playUnconfirmed = false) }
        playerStateManager.skipPrevious()
    }
    fun seekTo(fraction: Float)= playerStateManager.seekTo(fraction)
    fun toggleShuffle()        = playerStateManager.toggleShuffle()
    fun cycleRepeat()          = playerStateManager.cycleRepeat()
    fun setSleepTimer(m: Int)  = playerStateManager.setSleepTimer(m)

    // ── Like ──────────────────────────────────────────────────────────────────

    fun toggleLike() {
        val state    = _uiState.value
        val track    = state.currentTrack ?: return
        // The heart is hidden for an episode and for a local file (audit 2026-10-04 C4: a local
        // file's null id reached `removeFromLikedSongs` and crashed); belt and braces.
        val trackId  = track.likeTargetId ?: return
        val newLiked = !state.isLiked
        _uiState.update { it.copy(isLiked = newLiked) }
        viewModelScope.launch { applyLike(trackId, newLiked, track) }
    }

    /**
     * The server write behind a like, then the cache patch — ONLY on success (audit 2026-10-04 C4:
     * the Result used to be discarded, so a 403 / 429 / no-network left the heart and the persisted
     * Liked list claiming a change Spotify never made). On failure the optimistic flag is reverted
     * where the item is still the one shown, and a 429 arms the LIBRARY gate.
     */
    private suspend fun applyLike(trackId: String, liked: Boolean, track: SpotifyTrack?) {
        val result = if (liked) repository.saveTrack(trackId) else repository.removeTrack(trackId)
        result.fold(
            onSuccess = {
                withContext(Dispatchers.IO) {
                    if (liked) track?.let { libraryCache.prependToLikedSongs(it) }
                    else libraryCache.removeFromLikedSongs(trackId)
                }
            },
            onFailure = { e ->
                if (e.isRateLimited()) playerStateManager.noteRateLimited(e, "me/library", RateLimitFamily.LIBRARY)
                Log.d(TAG, "like: ${if (liked) "save" else "remove"} $trackId failed — ${e.message?.take(120)}")
                _uiState.update { ui ->
                    if (ui.currentTrack?.likeTargetId == trackId) ui.copy(isLiked = !liked) else ui
                }
            },
        )
    }

    /**
     * Sets the like state for a track identified by [uri] to the TARGET [liked]. Used by the Classic's
     * options menu, where the track may or may not be the current one. Mirrors [toggleLike]'s
     * behaviour: optimistic UI update (only when [uri] IS the current track), server call, cache
     * patch. Never for an episode uri.
     */
    fun setLiked(uri: String, liked: Boolean, track: SpotifyTrack? = null) {
        // Only a `spotify:track:` uri has a like target (audit 2026-10-04 W12): an episode's never
        // did, and a local file's `substringAfterLast(':')` was its DURATION — `spotify:track:127`.
        val trackId = trackIdOf(uri) ?: return
        // Optimistic UI flip — only when the uri matches what the player is showing.
        val currentTrack = _uiState.value.currentTrack?.takeIf { it.uri == uri }
        if (currentTrack != null) {
            _uiState.update { it.copy(isLiked = liked) }
        }
        // The full track for the liked-songs cache patch: the caller's (the iLyra has it for a
        // liked-list pick even while our currentTrack lags behind), else ours when it matches.
        val fullTrack = track?.takeIf { it.uri == uri } ?: currentTrack
        viewModelScope.launch { applyLike(trackId, liked, fullTrack) }
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

    /**
     * Plays a Liked Songs row as Spotify's own "Liked Songs" context: `context_uri =
     * spotify:user:<id>:collection` + `offset.uri` = the tapped song — the body Spotify's own
     * clients send, so the queue is the WHOLE library (no 750-song cap) and the Spotify app shows
     * "Liked Songs" as the context (Cris, 2026-10-04, after a device pass passed every step). The
     * 750-uri `uris` window this replaced was a workaround for a 2021 400 ("Can't have offset for
     * context type: COLLECTION") that is long gone; the 2026-09-25 revert to it happened under a
     * library-family ban with the mid-September Spotify regression already rolling out
     * (docs/CACHING.md → Liked Songs).
     *
     * The window survives ONLY as the body when the user id is not cached yet (no collection uri
     * to address). The collection is marked sent by [startPlay], so an EMPTY player falls back to
     * the track's ALBUM ([PlayFallbacks]); a collection body with the user's shuffle on is
     * bracketed (OFF confirmed → play → ON after confirmation) exactly as before.
     */
    fun playFromLikedSongs(trackUri: String, shuffle: Boolean? = null) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                // distinctBy { it.id }: guard the play queue against a not-yet-healed cache that may
                // still hold duplicate liked songs (see LibraryViewModel pagination dedup).
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.distinctBy { it.id }
            }
            val idx = cached?.indexOfFirst { it.uri == trackUri } ?: -1
            // The cached row: shown under the spinner at once, and its album is the fallback
            // context if the play leaves Spotify EMPTY (2026-10-04). A row missing from the cache
            // (not indexed yet) still plays from the collection; its album is looked up only if
            // a fallback is needed.
            val tapped = cached?.getOrNull(idx)
            // The cached user id (read on IO) — null only before the library has ever loaded.
            val collection = loadCollectionUri()
            // No user id: the old window from the tapped song (cap 750). A uri missing from the
            // cache plays alone — a window from the TOP of the list would lead with a different
            // song, and a uris body plays its head.
            val window = if (collection != null) null
                else cached?.takeIf { idx >= 0 }?.drop(idx)?.map { it.uri }?.take(PlaybackOrigin.URI_CAP)
            startPlay(
                uri             = trackUri,
                contextUri      = collection,
                uris            = window,
                startPositionMs = null,
                shuffle         = shuffle,
                origin          = PlaybackOrigin.Liked,
                albumUri        = tapped?.albumContextUri(),
                track           = tapped,
            )
        }
    }

    // ── Playlist picker (thin delegations to the shared TrackActionsController) ──

    fun loadOwnedPlaylists() {
        val track = _uiState.value.currentTrack ?: return
        // Playlists hold tracks. The picker's membership check and its add/remove calls are all
        // track-only, so an episode must never reach it (the button is disabled too).
        if (track.isEpisode) return
        // Null for a local file (audit 2026-10-04 C3): nothing in the picker can address one.
        val target = track.toTrackActionTarget() ?: return
        trackActions.openPlaylistPickerFor(target)
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
                    Log.d(TAG, "device picker: " + (if (devices.isEmpty()) "[] (none listed)"
                                                    else devices.joinToString { it.describe() }))
                    _uiState.update { it.copy(devicePickerLoading = false, availableDevices = devices) }
                },
                onFailure = { e ->
                    Log.w(TAG, "device picker: me/player/devices failed — ${e.message?.take(300)}")
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
        // A transfer is a playback request like any other (review 2026-10-04): it supersedes an
        // older play's confirm or wake restore (which could otherwise rescue or send a body on this
        // phone after the user moved playback away), and anything the user does after it — a tap,
        // a pause — supersedes the transfer's own App Remote fallback below.
        val generation = playerStateManager.notePlayRequest()
        fun superseded() = playerStateManager.playGeneration != generation
        playerStateManager.lockIsPlaying()
        playerStateManager.lockTrack()
        // Captured BEFORE the transfer: the track lock is 3 s and the confirm below reads for 4 s,
        // so an EMPTY poll could otherwise null the very uri the App Remote fallback needs.
        val before = playerStateManager.state.value
        val heldTrack = before.currentTrack
        val heldProgressMs = before.progressMs
        val tappedAt = System.currentTimeMillis()
        // THIS phone? Identified on the list the user just tapped (no extra GET), strictly: the
        // remembered id or a handheld named like this phone ([isThisPhone]) — remembered from then on.
        // Last resort, NEVER remembered: the ONLY handheld listed, and only when no listed device is
        // named like this phone and no id is remembered for it (an unfolded Fold registers as a
        // Tablet, and its Connect name may match no hint). With this phone's id known and absent
        // from the list, the sole handheld is another phone, and the user's choice is respected.
        val listed = _uiState.value.availableDevices
        val hints = deviceNameHints()
        val target = listed.firstOrNull { it.id == deviceId }
        val strictlyThisPhone = target != null && isThisPhone(target)
        val soleHandheldGuess = target != null && !strictlyThisPhone && knownLocalDeviceId == null &&
            soleHandheld(listed)?.id == deviceId && listed.none { isThisPhone(it) }
        val targetIsThisPhone = strictlyThisPhone || soleHandheldGuess
        if (strictlyThisPhone) knownLocalDeviceId = deviceId
        // To this phone with a song to land: an owned WAKING operation, like a tap (review
        // 2026-10-04) — the spinner and the inert play button cover the seconds of silence until
        // Spotify reports this phone playing, instead of a ticking "playing" bar over nothing.
        val owned = targetIsThisPhone && heldTrack != null
        val owner = if (owned) ++wakingOwner else 0L
        if (owned) {
            playbackJob?.cancel()
            clearWakingUpJob?.cancel()
            _uiState.update { it.copy(isWakingUp = true) }
        }
        // What "This device" does: the App Remote plays the held track and seeks to where it was —
        // connect, then a superseded check, then the play (a pause during a slow connect must not
        // start audio), then a short confirm of its own.
        suspend fun playHereThroughSdk(track: SpotifyTrack) {
            playerStateManager.lockTrack()
            playerStateManager.lockIsPlaying()
            val ok = remoteManager.connectSuspend()
            if (superseded()) { Log.d(TAG, "transfer: superseded during the App Remote connect"); return }
            if (!ok) {
                Log.d(TAG, "transfer: App Remote could not connect")
                playerStateManager.releasePlayingOptimism()
                return
            }
            remoteManager.play(track.uri)
            val seekTo = heldProgressMs + (if (before.isPlaying) System.currentTimeMillis() - tappedAt else 0L)
            if (seekTo > 0L) {
                delay(SDK_SEEK_SETTLE_MS)
                if (superseded()) return
                remoteManager.seekTo(seekTo.coerceAtMost((track.durationMs - 1_000L).coerceAtLeast(0L)))
            }
            val deadline = System.currentTimeMillis() + TRANSFER_SDK_CONFIRM_MS
            while (System.currentTimeMillis() < deadline) {
                delay(WAKE_CONFIRM_POLL_MS)
                if (superseded() || playerStateManager.isRateLimited()) return
                playerStateManager.lockTrack()
                playerStateManager.lockIsPlaying()
                val obs = playerStateManager.pollOnce() ?: continue
                if (obs.itemUri != null && obs.isPlaying) {
                    Log.d(TAG, "transfer: App Remote play reported (${obs.itemUri} on ${obs.deviceId})")
                    return
                }
            }
            Log.d(TAG, "transfer: App Remote play not reported within ${TRANSFER_SDK_CONFIRM_MS} ms")
        }
        val job = viewModelScope.launch {
            if (owned) ownedWakeRestores++
            try {
                repository.transferPlayback(deviceId).fold(
                    onSuccess = {
                        _uiState.update { it.copy(playUnconfirmed = false, spotifyNotListed = false) }
                        Log.d(TAG, "transfer: PUT me/player → ${target?.describe() ?: deviceId} accepted " +
                                   "(this phone=$targetIsThisPhone${if (soleHandheldGuess) " (the only handheld)" else ""}, " +
                                   "named like it=${target?.let { isNamedLikeThisPhone(it, hints) }}, hints=$hints)")
                        if (!owned) {
                            delay(700L)
                            playerStateManager.fetchOnce()
                            return@fold
                        }
                        // 2026-10-04: a transfer to this phone leaves Spotify Android 9.1.88 EMPTY (or
                        // not reporting it at all), while the App Remote ("This device") plays. Read
                        // me/player for a few seconds; this phone EMPTY, a 204 or another device still
                        // active is redone through the SDK — the device picker's own entry must work as
                        // well as the "This device" button.
                        val deadline = System.currentTimeMillis() + TRANSFER_CONFIRM_MS
                        var lastShape = "no answer"
                        var last: PollObservation? = null
                        var landed = false
                        while (System.currentTimeMillis() < deadline) {
                            delay(700L)
                            if (superseded()) { Log.d(TAG, "transfer: superseded while confirming"); return@fold }
                            playerStateManager.lockTrack()
                            playerStateManager.lockIsPlaying()
                            val obs = playerStateManager.pollOnce() ?: continue
                            last = obs
                            lastShape = when {
                                obs.status == 204          -> "204 (no active device)"
                                obs.deviceId != deviceId   -> "another device (${obs.deviceId}) still active"
                                obs.itemUri == null && !obs.isPlaying -> "EMPTY on this phone"
                                else                       -> "item=${obs.itemUri} playing=${obs.isPlaying}"
                            }
                            if (obs.deviceId == deviceId && obs.itemUri != null && obs.isPlaying) { landed = true; break }
                        }
                        if (landed) {
                            Log.d(TAG, "transfer: this phone reports ${playerStateManager.state.value.currentTrack?.uri} playing")
                            return@fold
                        }
                        if (superseded()) return@fold
                        // The transfer LANDED its item on this phone, just not playing yet: Spotify has
                        // it, and an SDK replay would restart the song.
                        if (last != null && last.deviceId == deviceId && last.itemUri != null) {
                            Log.d(TAG, "transfer: this phone holds ${last.itemUri} (not playing yet) — no App Remote replay")
                            return@fold
                        }
                        Log.d(TAG, "transfer: this phone not reported playing within ${TRANSFER_CONFIRM_MS} ms " +
                                   "(last: $lastShape) — the App Remote plays ${heldTrack.uri}, as \"This device\" does")
                        playHereThroughSdk(heldTrack)
                    },
                    onFailure = { e ->
                        if (e.isRateLimited()) playerStateManager.noteRateLimited(e)
                        Log.d(TAG, "transfer: PUT me/player → $deviceId failed — ${e.message?.take(160)}")
                        // A 404 on a transfer to THIS phone ("device not found" — it dropped off
                        // Connect) is what the App Remote fixes; anything else is shown.
                        if (owned && e.isNoActiveDevice() && !superseded()) {
                            Log.d(TAG, "transfer: 404 to this phone — the App Remote plays ${heldTrack.uri}")
                            playHereThroughSdk(heldTrack)
                        } else {
                            _uiState.update { it.copy(deviceTransferError = e.message) }
                        }
                    },
                )
            } finally {
                if (owned) {
                    ownedWakeRestores--
                    if (wakingOwner == owner) clearIsWakingUp()
                }
            }
        }
        if (owned) playbackJob = job
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
                    if (e.isRateLimited()) playerStateManager.noteRateLimited(e)
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
        /**
         * `spotify:album:<id>` of the tapped track when the caller knows it — the context a lone
         * track falls back to when a `uris` body leaves Spotify EMPTY (2026-10-04). Null = looked
         * up with one `GET tracks/{id}`, and only if a fallback is actually needed.
         */
        albumUri       : String?       = null,
        /** The tapped track itself when the caller has it: shown under the spinner at once. */
        track          : SpotifyTrack? = null,
    ) {
        startPlay(
        uri             = uri,
        contextUri      = contextUri,
        uris            = uris,
        startPositionMs = startPositionMs,
        shuffle         = shuffle,
        origin          = when {
            contextUri != null -> PlaybackOrigin.forContext(contextUri)
            else               -> PlaybackOrigin.forUris(uris ?: listOf(uri))
        },
        albumUri        = albumUri,
        track           = track,
    )
    }

    /**
     * One play Lyra issues — a row tap, a Search result, a Liked song. Since 2026-10-04 an accepted
     * body is NOT a playing song (Spotify Android 9.1.88 accepts a bare `uris` body and then stops
     * with an EMPTY player): the waking state holds until `me/player` REPORTS the song playing
     * ([awaitReportedPlaying]), an EMPTY player falls back to the track's context
     * ([PlayFallbacks] — the album; the collection for a Liked origin that went out as a window
     * because no user id was cached), and a `uris` play goes
     * straight to that context while uris bodies are suspect
     * (`PlayerStateManager.urisBodiesSuspect`). docs/PLAYER.md → Bare-uris plays stop the 9.1.88
     * client.
     */
    private fun startPlay(
        uri            : String,
        contextUri     : String?,
        uris           : List<String>?,
        startPositionMs: Long?,
        shuffle        : Boolean?,
        origin         : PlaybackOrigin,
        albumUri       : String?       = null,
        track          : SpotifyTrack? = null,
    ) {
        val isEpisode = uri.startsWith("spotify:episode:")
        // What was showing before this play — read before the seed below replaces it.
        val priorUri = playerStateManager.state.value.currentTrack?.uri
        // Recorded BEFORE anything is sent, and it bumps the play-request generation: an older
        // wake restore still waiting for its device sees the bump and abandons silently.
        playerStateManager.recordPlayOrigin(origin)
        val generation = playerStateManager.playGeneration
        // The tapped item is HELD in the mirror until Spotify reports it (an item=null poll no
        // longer blanks the player under the spinner), and shown at once when the caller has it.
        playerStateManager.holdPendingItem(
            uri, generation, System.currentTimeMillis() + PLAY_CONFIRM_CEILING_MS, priorUri,
        )
        val seed = track?.takeIf { it.uri == uri }
        if (seed != null) playerStateManager.seedPendingItem(seed, contextUri)
        playerStateManager.setOptimisticallyPlaying()
        playerStateManager.resetProgressForNewTrack()
        _uiState.update {
            it.copy(isPlaying = true, isLiked = false, error = null, isWakingUp = true,
                    playUnconfirmed = false, spotifyNotListed = false)
        }
        // `isLiked` was just cleared above, which is already the right answer for an episode —
        // and checkIsLiked would otherwise ask me/tracks/contains about `spotify:track:<episode
        // id>`, i.e. about a different item entirely. me/player/play takes episode uris in `uris`
        // (the `uri` branch of repository.play), so nothing else here needs to change.
        // A SEEDED new item already triggers the state collector's own check (the track id
        // changed) — a second `me/tracks/contains` per tap is not spent on a family that was
        // banned for hours (2026-09-25).
        if (!isEpisode && (seed == null || priorUri == uri)) {
            // Behind the `spotify:track:` prefix only (audit W12) — a local uri has no like target.
            trackIdOf(uri)?.let { trackId -> viewModelScope.launch { checkIsLiked(trackId) } }
        }
        // Ownership moves BEFORE the old job is cancelled: its `finally` clears the waking state
        // only while it still owns it, so a newer play is never robbed of its own spinner.
        val owner = ++wakingOwner
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            // This play OWNS the waking state from its first statement (review 2026-10-04): a PSM
            // wake operation it superseded (an SDK resume, a 404 skip) fires onWakeOperationComplete,
            // which clears only while nothing here is counted — before, the count began only in the
            // confirm, after the shuffle bracket and the PUT, and the older operation's clear took
            // this tap's spinner away.
            ownedWakeRestores++
            try {
                val fallbacks = PlayFallbacks(uri, origin, albumUri ?: seed?.albumContextUri(), isEpisode)
                // An album-row tap already IS the album body: never "fall back" to the same body.
                if (contextUri != null) fallbacks.markSent(contextUri)
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
                // The context actually being sent — the caller's, or the uris-suspect fallback below.
                var ctx = contextUri
                // 2026-10-04: once a uris body has been seen to leave Spotify EMPTY, a uris play goes
                // straight to its context fallback. Context bodies are never bracketed, so this is
                // decided BEFORE the shuffle bracket below. Only a context known WITHOUT a network
                // call (`nextKnown`): no `GET tracks/{id}` before the first play — with the album
                // unknown the uris body goes out and the EMPTY path looks the album up.
                if (ctx == null && !isEpisode && playerStateManager.urisBodiesSuspect()) {
                    val fb = fallbacks.nextKnown()
                    if (fb != null) {
                        Log.d(TAG, "play: uris bodies suspect — playing from ${fb.contextUri}")
                        fallbacks.markSent(fb.contextUri)
                        ctx = fb.contextUri
                        playUris = null
                    }
                }
                // BUG C (2026-09-25): see the `shuffle` KDoc. The user's setting is the mirror OR a
                // bracket still owed ON (a previous bracket's OFF has not been undone yet — see
                // PlayerStateManager.shuffleOwedOn). The mirror can be stale on a cold start (defaults
                // false), which only means the bracket is skipped, never misapplied.
                if (shuffle != null) playerStateManager.clearShuffleOwed()
                val owedOn = playerStateManager.shuffleOwedOn
                val userShuffleOn = playerStateManager.state.value.shuffleEnabled || owedOn
                val multiUriBody = ctx == null && (playUris?.size ?: 0) > 1
                // The Liked COLLECTION context is bracketed too: started with shuffle ON and an offset
                // Spotify never reported the item playing (device pass 2026-09-25 pm — every failure
                // that evening was collection + shuffle on; shuffle off worked). OFF (confirmed) →
                // play → ON re-asserted after, so the tapped song plays and the rest is shuffled.
                // Read off the context actually SENT: every Liked tap sends the collection since
                // 2026-10-04 evening (playFromLikedSongs), and the uris-suspect fallback can too.
                val collectionBody = COLLECTION_BRACKET_ENABLED && ctx != null && isCollectionContext(ctx)
                val bracketShuffle = shuffle == null && (multiUriBody || collectionBody) && userShuffleOn
                // Device pass 2026-09-25 (checklist A3): Spotify DROPPED shuffle when a playlist
                // context was started right after a `uris` playback, though it had kept it that
                // morning when the previous playback was itself a context. So whenever the user's
                // shuffle is on and the caller left it to us, shuffle is re-asserted ON after EVERY
                // play Spotify REPORTS playing — the bracket's second half, now for context and single
                // bodies too. A context's offset is honoured with shuffle on, so the tapped song keeps
                // playing and only the rest is (re)shuffled; an ON that Spotify kept is idempotent.
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
                    if (err != null && err.isRateLimited()) playerStateManager.noteRateLimited(err)
                    // Only an OFF before a multi-uri body can start the wrong song; an ON before a
                    // context / single body cannot, so it is not worth a round trip.
                    if (err == null && !effectiveShuffle && (multiUriBody || collectionBody)) confirmShuffleState(false)
                }
                var result = repository.play(
                    uri        = uri,
                    contextUri = ctx,
                    offsetUri  = if (ctx != null) uri else null,
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
                        contextUri = ctx,
                        offsetUri  = if (ctx != null) uri else null,
                        uris       = null,
                    )
                    val retryError = result.exceptionOrNull()
                    if (retryError != null) {
                        Log.w(TAG, "single-uri retry also failed", retryError)
                        // Noted HERE as well as in onFailure below: when the retry is rate limited the
                        // original rejection is what gets surfaced (next line), so the 429 would never
                        // reach the failure branch and the backoff gate would never be armed.
                        if (retryError.isRateLimited()) playerStateManager.noteRateLimited(retryError)
                        // A 404 on the retry means "no active device", which the App Remote fallback
                        // below can still fix — keep it so that path runs. Anything else: surface the
                        // ORIGINAL rejection, which names the real cause rather than its second symptom.
                        if (!retryError.isNoActiveDevice()) result = Result.failure(firstError)
                    }
                }
                // A REFUSED Liked collection body (a 4xx — e.g. a stale cached user id after signing
                // in to another account: logout does not clear the library cache) is not the end of
                // the tap: the album `PlayFallbacks` already holds goes out instead, positioned on
                // the same song. The collection was marked sent, so `next()` cannot repeat it; an
                // unknown album costs the one `GET tracks/{id}`, only on this path.
                if (firstError != null && ctx != null && isCollectionContext(ctx) && firstError.isRequestRefused()) {
                    val fb = if (playerStateManager.isRateLimited()) null else fallbacks.next()
                    if (fb != null) {
                        Log.w(TAG, "play() refused the collection body (${firstError.message}); " +
                                   "retrying with ${fb.describe()}", firstError)
                        fallbacks.markSent(fb.contextUri)
                        ctx = fb.contextUri
                        result = repository.play(
                            uri        = uri,
                            contextUri = ctx,
                            offsetUri  = uri,
                            uris       = null,
                        )
                        val retryError = result.exceptionOrNull()
                        if (retryError != null) {
                            Log.w(TAG, "album retry also failed", retryError)
                            if (retryError.isRateLimited()) playerStateManager.noteRateLimited(retryError)
                            // As above: a 404 keeps the wake path (now with the album); anything
                            // else surfaces the ORIGINAL refusal.
                            if (!retryError.isNoActiveDevice()) result = Result.failure(firstError)
                        }
                    }
                }
                val sentBody: PlayBody = ctx?.let { PlayBody.Context(it, uri) }
                    ?: PlayBody.Uris(playUris ?: listOf(uri))
                Log.d(TAG, "play: ${sentBody.describe()} for $uri → " +
                           (result.exceptionOrNull()?.message?.take(160) ?: "accepted"))
                // After the PUT returned: a local snapshot that confirms must be later than this.
                val acceptedAtElapsed = SystemClock.elapsedRealtime()
                result.fold(
                    onSuccess = {
                        awaitReportedPlaying(
                            uri             = uri,
                            acceptedAtElapsed = acceptedAtElapsed,
                            priorUri        = priorUri,
                            firstBody       = sentBody,
                            fallbacks       = fallbacks,
                            generation      = generation,
                            owner           = owner,
                            reassertOn      = reassertOn,
                            startPositionMs = startPositionMs,
                            // The album the refusal arm swapped in is NOT the caller's context.
                            callerContext   = contextUri != null && ctx == contextUri,
                        )
                    },
                    onFailure = { e ->
                        if (e.isNoActiveDevice()) {
                            val body = when {
                                ctx      != null -> WakeRestoreBody.Context(ctx)
                                // D' (Cris, 2026-10-04): the uris body goes out as in 4.1.0, so a user
                                // on an unaffected Spotify keeps the queue after a wake. Only while
                                // uris bodies are SUSPECT (one already left the player EMPTY in this
                                // process — on 9.1.88 it would SILENCE the SDK's audio) does a context
                                // known without a network call replace it; the confirm step falls back
                                // from either.
                                playUris != null -> wakeBodyWhileSuspect(WakeRestoreBody.Uris(playUris), fallbacks)
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
                                fallbacks       = fallbacks,
                            )
                        } else {
                            // Share the one 60-second penalty window with every other caller rather
                            // than letting this path fire again into an active limit (docs/SPOTIFY.md →
                            // Rate Limiting); the device-transfer and volume paths do the same.
                            if (e.isRateLimited()) playerStateManager.noteRateLimited(e)
                            // The bracket turned the user's shuffle OFF; a failed play must not leave
                            // it that way. (Not inside a rate limit — that PUT would only be refused.)
                            else if (reassertOn) restoreShuffleOn(owner)
                            playerStateManager.releasePendingItem(generation)
                            playerStateManager.releasePlayingOptimism()
                            _uiState.update { it.copy(error = e.message, isPlaying = false) }
                            clearIsWakingUp()
                        }
                    },
                )
            } finally {
                ownedWakeRestores--
                // A cancelled tap (a newer play took ownership first, so this is a no-op then) or any
                // path that returned without its own clear never strands the spinner.
                if (wakingOwner == owner) clearIsWakingUp()
            }
        }
    }

    /**
     * The fallback chain of one play (2026-10-04): the context bodies `fallbackBodies` names — the
     * user's collection for a Liked origin, then the track's album — each positioned on [uri] by
     * `offset.uri`, never one already sent. Everything is read LAZILY: the collection uri (the
     * cached user id) only for a Liked origin and only when a fallback is asked for, and an
     * unknown album only through [next], with ONE `GET tracks/{id}` once nothing else is left.
     */
    private inner class PlayFallbacks(
        private val uri      : String,
        private val origin   : PlaybackOrigin?,
        private var albumUri : String?,
        private val isEpisode: Boolean,
    ) {
        private val sent = mutableSetOf<String>()
        private var collectionUri: String? = null
        private var collectionLoaded = false
        private var albumLookedUp = false

        fun markSent(contextUri: String) { sent += contextUri }

        /** The next untried context from what is known WITHOUT a network call. */
        suspend fun nextKnown(): PlayBody.Context? {
            if (isEpisode) return null
            if (origin is PlaybackOrigin.Liked && !collectionLoaded) {
                collectionLoaded = true
                collectionUri = loadCollectionUri()
            }
            return fallbackBodies(origin, albumUri, collectionUri, isEpisode)
                .firstOrNull { it.contextUri !in sent }
                ?.copy(offsetUri = uri)
        }

        /** [nextKnown], else the album looked up once with `GET tracks/{id}`. */
        suspend fun next(): PlayBody.Context? {
            nextKnown()?.let { return it }
            if (isEpisode || albumUri != null || albumLookedUp) return null
            albumLookedUp = true
            albumUri = lookUpAlbumUri(uri)
            return nextKnown()
        }
    }

    /**
     * D' (Cris, 2026-10-04) — the body a wake restore SENDS for a planned [body]. A uris body stays
     * exactly as 4.1.0 sent it (a user on an unaffected Spotify keeps the URI queue after a wake)
     * unless uris bodies are SUSPECT in this process (`PlayerStateManager.urisBodiesSuspect` — one
     * has already left the player EMPTY; on 9.1.88 a uris body would SILENCE the SDK's audio):
     * then the first fallback context known WITHOUT a network call — the collection for a Liked
     * origin when the user id is cached, else the item's album — replaces it, marked sent. Nothing
     * known (an episode, an unknown album) → the uris body; the confirm step falls back from there.
     */
    private suspend fun wakeBodyWhileSuspect(body: WakeRestoreBody, fallbacks: PlayFallbacks): WakeRestoreBody {
        if (body !is WakeRestoreBody.Uris || !playerStateManager.urisBodiesSuspect()) return body
        val fb = fallbacks.nextKnown() ?: return body
        Log.d(TAG, "wake: uris bodies suspect — restoring from ${fb.contextUri}")
        fallbacks.markSent(fb.contextUri)
        return WakeRestoreBody.Context(fb.contextUri)
    }

    /** `spotify:user:<id>:collection` from the cached user (the library cache persists it). */
    private suspend fun loadCollectionUri(): String? =
        likedCollectionUri(withContext(Dispatchers.IO) { libraryCache.load()?.user?.id })

    /**
     * Until when a 429 from `GET tracks/{id}` stops further album lookups (epoch ms, main thread).
     * Its OWN window, not the PLAYER family's (review 2026-10-04): a catalog ban (`Retry-After`
     * can be hours, 2026-09-25) must not gate `me/player` — the poll, play/pause, every play —
     * which is exactly what the per-family split exists to prevent. Nor LIBRARY's, which drives the
     * Library warning icon. Honours `Retry-After` from 60 s up to 6 h, like LIBRARY.
     */
    private var albumLookupBackoffUntil = 0L

    /**
     * The album of [trackUri] — ONE `GET tracks/{id}`, called only when a fallback is actually
     * needed and nothing else is known (an older recent search, a deep link). Gated on the PLAYER
     * window (it only runs to send a play) and on its own catalog window.
     */
    private suspend fun lookUpAlbumUri(trackUri: String): String? {
        if (!trackUri.startsWith("spotify:track:")) return null
        if (playerStateManager.isRateLimited()) return null
        if (System.currentTimeMillis() < albumLookupBackoffUntil) return null
        val result = repository.getTrack(trackUri.substringAfterLast(':'))
        currentCoroutineContext().ensureActive()
        return result.fold(
            onSuccess = { t ->
                t.albumContextUri().also { Log.d(TAG, "play: album of $trackUri looked up → $it") }
            },
            onFailure = { e ->
                if (e.isRateLimited()) {
                    val retryAfterS = e.message?.let { Regex("Retry-After=(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    val backoffMs = ((retryAfterS ?: 0L) * 1_000L).coerceIn(60_000L, 6L * 60L * 60L * 1_000L)
                    albumLookupBackoffUntil = System.currentTimeMillis() + backoffMs
                    Log.w(TAG, "play: 429 from tracks/{id} — album lookups back off ${backoffMs / 1_000L} s " +
                               "(the player is not gated)")
                }
                Log.w(TAG, "play: album lookup for $trackUri failed — ${e.message?.take(160)}")
                null
            },
        )
    }

    /**
     * This phone's Spotify Connect id, once something identified it: the wake restore's device
     * wait ([pickLocalDevice] on the full list) or a device-picker transfer to it. Main thread only.
     */
    @Volatile private var knownLocalDeviceId: String? = null

    /**
     * The STRICT "is [device] this phone" — the id identified earlier, or a handheld NAMED like
     * this phone ([isNamedLikeThisPhone]). Never `pickLocalDevice`'s "the only Smartphone" rule:
     * the App Remote rescue and the transfer fallback PLAY on this phone, and taking another phone
     * for it would hijack the user's choice.
     */
    private fun isThisPhone(device: SpotifyDevice): Boolean {
        val id = device.id ?: return false
        return id == knownLocalDeviceId || isNamedLikeThisPhone(device, deviceNameHints())
    }

    /** Is the device the mirror holds under [id] THIS phone (the strict [isThisPhone])? For `webViewBlind`. */
    private fun deviceIdIsThisPhone(id: String): Boolean {
        val d = playerStateManager.state.value.currentDevice ?: return false
        return d.id == id && isThisPhone(d)
    }

    /**
     * Is the device that [obs] reports THIS phone? Read off the mirror's device (the same poll
     * wrote it). The SDK rescue plays on this phone, so it must never run over an EMPTY player on
     * another device (it would hijack it).
     */
    private fun observedDeviceIsThisPhone(obs: PollObservation): Boolean {
        val d = playerStateManager.state.value.currentDevice ?: return false
        if (obs.deviceId == null || d.id != obs.deviceId) return false
        return isThisPhone(d)
    }

    /**
     * After an ACCEPTED body: `me/player` every [PLAY_CONFIRM_POLL_MS] (optimistic lock re-armed)
     * until it REPORTS [uri] playing — Cris's rule, the waking state never clears on a timer —
     * for at most [PLAY_CONFIRM_CEILING_MS] from here (the pending hold is moved to match).
     *  - CONFIRMED → the shuffle debt is settled (the re-assert moved here, after confirmation),
     *    the hold released, the spinner cleared.
     *  - Another item (not the prior one) reported PLAYING [OTHER_ITEM_PLAYING_STREAK] polls in a
     *    row → audio is playing and the mirror shows it: the same, without "didn't report".
     *  - EMPTY on two consecutive polls (five after the caller's OWN context, [callerContext] — a
     *    playlist / album row or a Liked tap's collection; such a body starts at once on 9.1.88 and a slow start must not lose its
     *    context) → a uris body arms the uris-suspect flag; the next [PlayFallbacks] body goes
     *    out; with none left, the App Remote plays the item ONCE (the SDK is proven to produce
     *    audio) — only when the EMPTY device is this phone — with a confirm window of its own.
     *  - OTHER_ITEM / NO_DEVICE → keep polling. A FAILED poll counts as nothing.
     *  - Ceiling → with no item reported, [PlayerUiState.playUnconfirmed]: the tapped song stays on
     *    screen, paused, under one quiet line (the hold kept against `item: null` only); with an
     *    item reported, the mirror simply shows it.
     * Superseded (the generation moved) → stops silently: the bracket's shuffle is restored and the
     * waking state cleared only by its owner (a newer play here settles both itself).
     */
    private suspend fun awaitReportedPlaying(
        uri            : String,
        /** `elapsedRealtime` once the body was accepted — the SDK confirm reads only later snapshots. */
        acceptedAtElapsed: Long,
        priorUri       : String?,
        firstBody      : PlayBody,
        fallbacks      : PlayFallbacks,
        generation     : Long,
        owner          : Long,
        reassertOn     : Boolean,
        startPositionMs: Long?,
        callerContext  : Boolean,
    ) {
        fun superseded() = playerStateManager.playGeneration != generation
        fun stopSuperseded(stage: String) {
            Log.d(TAG, "play: superseded $stage ($uri)")
            // A pause / skip / transfer superseded it — none of them settles a bracket: undo it now.
            if (wakingOwner == owner && reassertOn) restoreShuffleOn(owner)
        }
        var kept = false
        try {
            val startedAt = System.currentTimeMillis()
            var deadline = startedAt + PLAY_CONFIRM_CEILING_MS
            playerStateManager.extendPendingItem(generation, deadline + PLAY_CONFIRM_POLL_MS)
            var body = firstBody.describe()
            var bodyIsUris = firstBody is PlayBody.Uris
            val emptyNeeded = if (callerContext) CALLER_CONTEXT_EMPTY_STREAK else 2
            var emptyStreak = 0
            var otherStreak = 0
            var sdkRescued = false
            // SDK_REPORT_CONFIRMS_PLAY: while me/player is BLIND the local app's own report confirms
            // (SDK mirror, 2026-10-04 evening). Never on a re-tap of the song already showing: the
            // local app keeps the OLD playback of that uri advancing for ~1.3 s before 9.1.88 stops it.
            var sdkWatch = SdkAudibleWatch(targetUri = uri, dispatchedAtElapsed = acceptedAtElapsed)
            var sdkMayConfirm = SDK_REPORT_CONFIRMS_PLAY && uri != priorUri
            while (System.currentTimeMillis() < deadline) {
                delay(PLAY_CONFIRM_POLL_MS)
                if (superseded()) { stopSuperseded("while confirming"); return }
                if (playerStateManager.isRateLimited()) {
                    Log.d(TAG, "play: rate limited while confirming $uri")
                    break
                }
                playerStateManager.lockIsPlaying()
                val obs = playerStateManager.pollOnce() ?: continue
                currentCoroutineContext().ensureActive()
                // The blind poll has just re-read the local app (PlayerStateManager.mirrorFromLocalApp).
                if (sdkMayConfirm && webViewBlind(obs, ::deviceIdIsThisPhone) &&
                    sdkWatch.offer(remoteManager.localState.value)) {
                    Log.d(TAG, "play: the local Spotify app reports $uri playing (me/player blind) after " +
                               "${System.currentTimeMillis() - startedAt} ms via $body")
                    playerStateManager.releasePendingItem(generation)
                    clearIsWakingUp()
                    if (reassertOn) restoreShuffleOn(owner)
                    return
                }
                otherStreak = if (isOtherItemPlaying(obs, uri, priorUri)) otherStreak + 1 else 0
                if (otherStreak >= OTHER_ITEM_PLAYING_STREAK) {
                    Log.d(TAG, "play: Spotify reports ${obs.itemUri} playing instead of $uri " +
                               "(${System.currentTimeMillis() - startedAt} ms, via $body) — taken as playing")
                    playerStateManager.releasePendingItem(generation)
                    clearIsWakingUp()
                    if (reassertOn) restoreShuffleOn(owner)
                    return
                }
                when (confirmVerdict(obs, uri, priorUri)) {
                    ConfirmVerdict.CONFIRMED -> {
                        Log.d(TAG, "play: confirmed after ${System.currentTimeMillis() - startedAt} ms via $body")
                        playerStateManager.releasePendingItem(generation)
                        clearIsWakingUp()
                        if (reassertOn) restoreShuffleOn(owner)
                        return
                    }
                    ConfirmVerdict.EMPTY -> {
                        if (++emptyStreak < emptyNeeded) continue
                        emptyStreak = 0
                        if (bodyIsUris) playerStateManager.noteUrisBodyEmptied()
                        var next = if (playerStateManager.isRateLimited()) null else fallbacks.next()
                        var sentNext = false
                        while (next != null && !superseded()) {
                            fallbacks.markSent(next.contextUri)
                            Log.d(TAG, "play: $body left the player EMPTY; falling back to ${next.describe()}")
                            val err = repository.play(contextUri = next.contextUri, offsetUri = uri).exceptionOrNull()
                            currentCoroutineContext().ensureActive()
                            Log.d(TAG, "play: ${next.describe()} → ${err?.message?.take(160) ?: "accepted"}")
                            body = next.describe()
                            bodyIsUris = false
                            if (err == null) { sentNext = true; break }
                            if (err.isRateLimited()) { playerStateManager.noteRateLimited(err, "play fallback"); break }
                            next = fallbacks.next()
                        }
                        if (superseded()) { stopSuperseded("during the fallback"); return }
                        if (sentNext) {
                            // The new body gets a confirm window of its own, and the hold with it.
                            deadline = maxOf(deadline, System.currentTimeMillis() + PLAY_CONFIRM_RESCUE_MS)
                            playerStateManager.extendPendingItem(generation, deadline + PLAY_CONFIRM_POLL_MS)
                            continue
                        }
                        if (!sdkRescued) {
                            sdkRescued = true
                            if (observedDeviceIsThisPhone(obs)) {
                                Log.d(TAG, "play: $body left the player EMPTY and no context is left — " +
                                           "the App Remote plays $uri")
                                body = "the App Remote"
                                // Connect, THEN look again, THEN play: a real connect can take tens of
                                // seconds, and a pause made meanwhile must not be answered with audio.
                                val ok = remoteManager.connectSuspend()
                                if (superseded()) { stopSuperseded("during the App Remote connect"); return }
                                if (ok) {
                                    remoteManager.play(uri)
                                    // The rescue starts a FRESH playback after an EMPTY player: its own
                                    // report counts, even on a re-tap of the same song.
                                    sdkWatch = SdkAudibleWatch(targetUri = uri, dispatchedAtElapsed = SystemClock.elapsedRealtime())
                                    sdkMayConfirm = SDK_REPORT_CONFIRMS_PLAY
                                    if (startPositionMs != null && startPositionMs > 0L) {
                                        delay(SDK_SEEK_SETTLE_MS)
                                        if (superseded()) { stopSuperseded("before the App Remote seek"); return }
                                        remoteManager.seekTo(startPositionMs)
                                    }
                                    // The connect may have used up the ceiling: the rescue gets its own.
                                    deadline = maxOf(deadline, System.currentTimeMillis() + PLAY_CONFIRM_RESCUE_MS)
                                    playerStateManager.extendPendingItem(generation, deadline + PLAY_CONFIRM_POLL_MS)
                                } else {
                                    Log.d(TAG, "play: App Remote could not connect for the rescue")
                                }
                            } else {
                                Log.d(TAG, "play: $body left the player EMPTY on another device " +
                                           "(${obs.deviceId}) — no App Remote rescue")
                            }
                        }
                    }
                    ConfirmVerdict.OTHER_ITEM, ConfirmVerdict.NO_DEVICE -> emptyStreak = 0
                }
            }
            if (superseded()) { stopSuperseded("at the confirm ceiling"); return }
            val latest = playerStateManager.lastPollValue()
            Log.d(TAG, "play: unconfirmed after ${System.currentTimeMillis() - startedAt} ms — $uri via $body " +
                       "was never reported playing (last poll: item=${latest?.itemUri} playing=${latest?.isPlaying})")
            playerStateManager.releasePlayingOptimism()
            if (latest?.itemUri == null) {
                // Nothing reported: the tapped song stays, paused, under the line — ready for the
                // play button's SDK resume / 404 restore — until a poll reports any item.
                kept = true
                playerStateManager.keepPendingItemUnconfirmed(generation)
                _uiState.update { it.copy(playUnconfirmed = true, isPlaying = false) }
            } else {
                // Spotify reports an item (the previous song, the target paused): the mirror shows
                // it, and a line under it would be gone at the next poll anyway.
                playerStateManager.releasePendingItem(generation)
                _uiState.update { it.copy(isPlaying = false) }
            }
            clearIsWakingUp()
            if (reassertOn) restoreShuffleOn(owner)
        } finally {
            if (!kept) playerStateManager.releasePendingItem(generation)
            if (wakingOwner == owner) clearIsWakingUp()
        }
    }

    private fun PlayBody.describe(): String = when (this) {
        is PlayBody.Context -> "context $contextUri" + (offsetUri?.let { " @$it" } ?: "")
        is PlayBody.Uris    -> "uris ×${uris.size}"
    }

    /**
     * The ONE App Remote wake path — a track tap whose `me/player/play` answered 404 (no active
     * device) and the play button after Spotify died while paused both end here
     * (docs/PLAYER.md → Playback 404 Fallback).
     *
     *  1. The caller has set the waking state and the optimistic playing lock; both are held until
     *     step 5 — Cris's rule: the player shows WAKING until Spotify REPORTS the song playing,
     *     never cleared on a timer or on an IPC dispatch. Since 2026-10-04 evening the LOCAL app's
     *     report counts during step 4 ([SDK_REPORT_CONFIRMS_PLAY]: two local snapshots, not paused,
     *     the position advanced ≥ 500 ms) — the spinner clears there and the wait goes on behind it.
     *  2. [shuffle] (when non-null) goes through the App Remote first, with its settle delay.
     *  3. The SDK plays the single item for instant audio (and seeks to [startPositionMs] after
     *     [SDK_SEEK_SETTLE_MS] when known). `connectSuspend` resumes only after Spotify's
     *     `onConnected`, so a cold Spotify's boot is spent before this returns.
     *  4. `me/player/devices` is polled every [WAKE_DEVICE_POLL_MS] until THIS phone is listed
     *     ([pickLocalDevice]) — up to [WAKE_DEVICE_TIMEOUT_MS] — and [body] is sent ONCE with its
     *     `device_id`: with it the device only has to be listed, not active. A 404 there is
     *     "listed but not ready" and goes back to polling; a 429 abandons; a refused multi-uri
     *     body degrades once to [degradeTo]. At the ceiling NO body is sent (2026-10-04: one
     *     without `device_id` could only 404 or hijack another device) — the SDK keeps playing the
     *     single item. After [WAKE_NOT_LISTED_HINT_MS] unlisted, the player shows the quiet
     *     "Open Spotify" hint ([PlayerUiState.spotifyNotListed]).
     *  5. `me/player` is re-read every [WAKE_CONFIRM_POLL_MS] (up to [WAKE_CONFIRM_TIMEOUT_MS])
     *     until it reports [uri] PLAYING ([confirmVerdict]). An EMPTY player twice in a row sends
     *     the next [fallbacks] context (collection → album) — after a uris [body], first arming
     *     the uris-suspect flag (`noteUrisBodyEmptied`) — and with none left the App Remote
     *     plays the item again, once. Then — or on a definitive failure, or at the ceiling — the
     *     waking state clears.
     *
     * [body] is what the caller planned. D' (Cris, 2026-10-04): a uris body is the 4.1.0 URI list
     * — the callers swap it for a context ([wakeBodyWhileSuspect]) only while uris bodies are
     * already SUSPECT in this process, so a user on an unaffected Spotify keeps the queue.
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
        /** +1 / -1: after the body lands, skip next / previous (a skip on a dead Spotify). */
        thenSkip       : Int = 0,
        /**
         * The context bodies to send if an accepted body leaves the player EMPTY (item null, not
         * playing) — a collection body after a wake, or a uris body on Spotify Android 9.1.88.
         * The caller has already marked [body] itself as sent.
         */
        fallbacks      : PlayFallbacks? = null,
    ) {
        fun superseded() = playerStateManager.playGeneration != generation
        fun abandon(stage: String) {
            Log.d(TAG, "wake: superseded $stage — stopping")
            if (wakingOwner == owner) {
                // The hint belonged to this restore's device wait (review 2026-10-04).
                _uiState.update { it.copy(spotifyNotListed = false) }
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
            val dispatchedAtElapsed = SystemClock.elapsedRealtime()
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
            var landedDeviceId: String? = null
            var abandoned = false
            var failed = false
            var neverListed = false
            var bodyFailures = 0
            var listedLogged = false
            var lastDevicesLog: String? = null
            var notListedHinted = false
            // The local app no longer played [uri] when the body was about to go out (a pause, a
            // skip from the lockscreen / a headset / Spotify's own UI): the body is dropped.
            var bodyDropped = false
            // SDK_REPORT_CONFIRMS_PLAY: the local app's report, and whether it has already been
            // taken as "reporting the song playing" (the spinner is then gone; the wait goes on).
            val sdkWatch = SdkAudibleWatch(targetUri = uri, dispatchedAtElapsed = dispatchedAtElapsed)
            var sdkReportedPlaying = false
            val deadline = sdkStartedAt + WAKE_DEVICE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (superseded()) { abandon("while waiting for the device"); return }
                // Until the local app has reported the song audibly playing: the optimistic lock is
                // 5 s and the wait can be a minute — without re-arming it, 204 polls flip isPlaying
                // false under the notification, widget and visualizer gate; and a 200 with item=null
                // while the context loads must not show "Nothing Playing". AFTER that report the
                // locks are NOT re-armed (review 2026-10-04 evening): the SDK mirror carries the
                // state, so a pause made OUTSIDE Lyra (lockscreen, headset, Spotify's UI) shows.
                if (!sdkReportedPlaying) {
                    playerStateManager.lockIsPlaying()
                    playerStateManager.lockTrack()
                }
                if (playerStateManager.isRateLimited()) {
                    Log.d(TAG, "wake: rate limited while waiting for the device; abandoning the restore")
                    abandoned = true
                    break
                }
                if (SDK_REPORT_CONFIRMS_PLAY && !sdkReportedPlaying) {
                    // Two local snapshots after the dispatch, not paused, on the item, the position
                    // advanced ≥ 500 ms: the local app is audibly playing it. The spinner clears;
                    // the device wait (and the body) carries on behind it. The read is on demand —
                    // the subscription fires on changes, not as the position runs.
                    if (sdkWatch.offer(remoteManager.refreshLocalState() ?: remoteManager.localState.value)) {
                        sdkReportedPlaying = true
                        Log.d(TAG, "wake: the local Spotify app reports $uri playing after " +
                                   "${SystemClock.elapsedRealtime() - dispatchedAtElapsed} ms — spinner cleared, " +
                                   "the device wait continues")
                        clearIsWakingUp()
                    }
                    if (superseded()) { abandon("while reading the local app"); return }
                }
                val devices = repository.getAvailableDevices()
                currentCoroutineContext().ensureActive()
                val devicesError = devices.exceptionOrNull()
                // What the server LISTS, logged once per change (2026-10-04: a 60 s "never listed"
                // on device said nothing about whether the call failed or who was in the list).
                val devicesLog = devicesError?.let { "me/player/devices failed — ${it.message?.take(300)}" }
                    ?: devices.getOrNull()?.let { list ->
                        if (list.isEmpty()) "me/player/devices: [] (none listed)"
                        else "me/player/devices: " + list.joinToString { d -> d.describe() }
                    }
                if (devicesLog != lastDevicesLog) {
                    lastDevicesLog = devicesLog
                    Log.d(TAG, "wake: $devicesLog (hints=$hints)")
                }
                if (devicesError != null && devicesError.isRateLimited()) {
                    playerStateManager.noteRateLimited(devicesError, "wake me/player/devices")
                    Log.d(TAG, "wake: me/player/devices rate limited; abandoning the restore")
                    abandoned = true
                    break
                }
                val device = devices.getOrNull()?.let { pickLocalDevice(it, hints) }
                val deviceId = device?.id
                if (deviceId == null && !notListedHinted &&
                    System.currentTimeMillis() - sdkStartedAt >= WAKE_NOT_LISTED_HINT_MS) {
                    notListedHinted = true
                    Log.d(TAG, "wake: the phone is still not listed after ${WAKE_NOT_LISTED_HINT_MS} ms — " +
                               "showing the Open Spotify hint")
                    _uiState.update { it.copy(spotifyNotListed = true) }
                }
                if (deviceId != null) {
                    if (!listedLogged) {
                        listedLogged = true
                        Log.d(TAG, "wake: device listed after ${System.currentTimeMillis() - sdkStartedAt} ms " +
                                   "(${device.name} / $deviceId, active=${device.isActive})")
                        // Remembered only when NAMED like this phone (review 2026-10-04): an active
                        // handheld may be another phone of the account, and "the only Smartphone"
                        // may be one too — remembering either would make every later "is this
                        // phone" check (the SDK rescue, the transfer fallback) take it for this one.
                        if (isNamedLikeThisPhone(device, hints)) knownLocalDeviceId = deviceId
                        _uiState.update { it.copy(spotifyNotListed = false) }
                    }
                    val toSend = pending
                    if (toSend == null) {
                        // Nothing to rebuild — the SDK play IS the playback. Go and confirm it.
                        accepted = true
                        landedDeviceId = deviceId
                        break
                    }
                    // Last look before the body goes out: the device poll takes real time.
                    if (superseded()) { abandon("just before the body"); return }
                    // After the spinner cleared, a pause / skip / seek made OUTSIDE Lyra moves no
                    // play generation (review 2026-10-04 evening): read the local app now. Paused,
                    // on another item, or no state at all (the bind dropped) → the body is DROPPED
                    // (it would resume audio the user paused, or yank them back from the song they
                    // skipped to). A slow read (1 s IPC timeout) falls back to the subscription's
                    // last event, which fires on exactly those changes. Playing → the body starts
                    // from the local app's OWN position (a fresh read only; base + elapsed after a
                    // fallback). Only after the SDK's audible report: before it, a cold load still
                    // reads paused / stalled.
                    var positionOverrideMs: Long? = null
                    if (sdkReportedPlaying) {
                        val fresh = remoteManager.refreshLocalState()
                        val local = fresh ?: remoteManager.localState.value
                        currentCoroutineContext().ensureActive()
                        if (superseded()) { abandon("just before the body"); return }
                        if (local == null || local.isPaused || local.trackUri != uri) {
                            Log.d(TAG, "wake: the local app no longer reports $uri playing (" +
                                       (local?.let { l -> "${l.trackUri} ${if (l.isPaused) "paused" else "playing"}" }
                                           ?: "unreadable") + ") — ${toSend.describe()} dropped")
                            bodyDropped = true
                            break
                        }
                        if (positionMs != null && fresh != null) {
                            positionOverrideMs = local.positionMs +
                                (SystemClock.elapsedRealtime() - local.atElapsedMs).coerceAtLeast(0L)
                        }
                    }
                    val sent = sendWakeBody(toSend, uri, positionMs, sdkStartedAt, deviceId, positionOverrideMs)
                    currentCoroutineContext().ensureActive()
                    val err = sent.exceptionOrNull()
                    Log.d(TAG, "wake: body sent ${toSend.describe()} to $deviceId → " +
                               (err?.message ?: "accepted"))
                    when {
                        err == null -> { accepted = true; landedDeviceId = deviceId; break }
                        err.isRateLimited() -> {
                            // A 429 says nothing about the body; the same body is fine once the
                            // window passes. Arm the shared gate and stop — re-sending inside a
                            // penalty window is the hammering the gate exists to stop. The SDK is
                            // still playing the single item.
                            playerStateManager.noteRateLimited(err, "wake body")
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
                if (sdkReportedPlaying) {
                    // The spinner is gone; the wait only has to catch the phone being listed. A pause
                    // or a tap is still noticed within a second (this restore still owns its state).
                    var slept = 0L
                    while (slept < WAKE_DEVICE_POLL_AFTER_SDK_MS && !superseded()) {
                        delay(WAKE_SUPERSEDE_CHECK_MS)
                        slept += WAKE_SUPERSEDE_CHECK_MS
                    }
                } else {
                    delay(WAKE_DEVICE_POLL_MS)
                }
            }
            if (accepted && superseded()) {
                // The body went out in the window between the last check and the send. If what
                // superseded it was a PAUSE (the mirror is optimistically not playing), the body has
                // just restarted playback under the user's pause — undo that before stopping.
                if (!playerStateManager.state.value.isPlaying) {
                    Log.d(TAG, "wake: body landed after a pause; pausing again")
                    repository.pause()
                }
                abandon("after the body landed"); return
            }
            if (!accepted && !abandoned && !failed && !bodyDropped) {
                if (superseded()) { abandon("at the device ceiling"); return }
                if (pending == null) {
                    accepted = true
                } else {
                    // 2026-10-04: no body without `device_id` any more — with the phone unlisted it
                    // could only 404, or land on (and hijack) whichever OTHER device is active.
                    neverListed = true
                    Log.d(TAG, "wake: device never listed in ${WAKE_DEVICE_TIMEOUT_MS} ms — no body sent " +
                               "(${pending.describe()} dropped; the SDK plays the single item)")
                }
            }

            if (accepted && thenSkip != 0) {
                if (superseded()) { abandon("before the skip"); return }
                // A 404 here is "listed but not active yet" — the body was accepted a moment ago,
                // so a couple of short retries cover it.
                // ONE skip. (A `repeat` with `return@repeat` shipped for twenty minutes: that only
                // ends the iteration, so a successful skip was followed by two more — "sometimes
                // it skipped 3 tracks", device pass 2026-09-25.)
                var skipErr: Throwable? = null
                var attempts = 0
                while (true) {
                    val r = if (thenSkip > 0) repository.skipNext(landedDeviceId)
                            else repository.skipPrevious(landedDeviceId)
                    skipErr = r.exceptionOrNull()
                    val notReadyYet = skipErr?.isNoActiveDevice() == true
                    if (!notReadyYet || ++attempts >= 3) break
                    delay(WAKE_CONFIRM_POLL_MS)
                }
                if (skipErr?.isRateLimited() == true) playerStateManager.noteRateLimited(skipErr)
                Log.d(TAG, "wake: skip ${if (thenSkip > 0) "next" else "previous"} on $landedDeviceId → " +
                           (skipErr?.message ?: "accepted"))
            }
            var confirmed = false
            if (accepted) {
                var current = pending?.describe() ?: "the SDK play"
                var currentIsUris = pending is WakeRestoreBody.Uris
                // What the confirm must see (review 2026-10-04): the SDK is ALREADY playing [uri]
                // before the body goes out, so one CONFIRMED poll may be the SDK's own audio — a
                // body that then empties the player would never be caught. A context body confirms
                // on its own context reported; a uris body on two polls in a row (wakeConfirmReached).
                var bodySent = pending != null
                var sentContext = (pending as? WakeRestoreBody.Context)?.contextUri
                var confirmedStreak = 0
                var emptyStreak = 0
                var sdkReplayed = false
                // After the SDK's audible report cleared the spinner, the spinner (and the locks)
                // come back only on an EMPTY player — a body that silenced the SDK's audio (review
                // 2026-10-04 evening): never "playing" over the silence of the fallbacks.
                var wakingRearmed = false
                val confirmDeadline = System.currentTimeMillis() + WAKE_CONFIRM_TIMEOUT_MS
                while (System.currentTimeMillis() < confirmDeadline) {
                    delay(WAKE_CONFIRM_POLL_MS)
                    if (superseded() || playerStateManager.isRateLimited()) break
                    if (!sdkReportedPlaying || wakingRearmed) {
                        playerStateManager.lockIsPlaying()
                        playerStateManager.lockTrack()
                    }
                    val obs = playerStateManager.pollOnce() ?: continue
                    currentCoroutineContext().ensureActive()
                    // After a skip the target is whatever Spotify moved to: any OTHER item playing.
                    val verdict = confirmVerdict(obs, uri, priorUri = uri, step = thenSkip)
                    confirmedStreak = if (verdict == ConfirmVerdict.CONFIRMED) confirmedStreak + 1 else 0
                    when (verdict) {
                        ConfirmVerdict.CONFIRMED -> {
                            if (wakeConfirmReached(obs, bodySent, sentContext, confirmedStreak)) { confirmed = true; break }
                        }
                        ConfirmVerdict.EMPTY -> {
                            if (sdkReportedPlaying && !wakingRearmed && wakingOwner == owner) {
                                wakingRearmed = true
                                Log.d(TAG, "wake: $current left the player EMPTY after the local app's audible " +
                                           "report — the waking state is back until it is reported playing")
                                clearWakingUpJob?.cancel()
                                _uiState.update { it.copy(isWakingUp = true) }
                                playerStateManager.lockIsPlaying()
                                playerStateManager.lockTrack()
                            }
                            if (thenSkip != 0 || ++emptyStreak < 2) continue
                            emptyStreak = 0
                            if (currentIsUris) playerStateManager.noteUrisBodyEmptied()
                            var next = if (playerStateManager.isRateLimited()) null else fallbacks?.next()
                            var sentNext = false
                            while (next != null && !superseded()) {
                                fallbacks?.markSent(next.contextUri)
                                Log.d(TAG, "wake: $current left the player EMPTY; falling back to ${next.describe()}")
                                val err = repository.play(
                                    contextUri = next.contextUri,
                                    offsetUri  = uri,
                                    positionMs = positionMs?.plus(System.currentTimeMillis() - sdkStartedAt),
                                    deviceId   = landedDeviceId,
                                ).exceptionOrNull()
                                currentCoroutineContext().ensureActive()
                                Log.d(TAG, "wake: ${next.describe()} → ${err?.message?.take(160) ?: "accepted"}")
                                current = next.describe()
                                currentIsUris = false
                                if (err == null) {
                                    sentNext = true
                                    bodySent = true
                                    sentContext = next.contextUri
                                    confirmedStreak = 0
                                    break
                                }
                                if (err.isRateLimited()) { playerStateManager.noteRateLimited(err, "wake fallback body"); break }
                                next = fallbacks?.next()
                            }
                            if (sentNext || superseded() || sdkReplayed) continue
                            // Nothing left over the API: the SDK, proven to produce audio, plays
                            // the item again — once.
                            sdkReplayed = true
                            Log.d(TAG, "wake: $current left the player EMPTY and no context is left — " +
                                       "the App Remote plays $uri again")
                            current = "the App Remote replay"
                            currentIsUris = false
                            bodySent = false
                            sentContext = null
                            confirmedStreak = 0
                            remoteManager.play(uri)
                            if (startPositionMs != null && startPositionMs > 0L) {
                                delay(SDK_SEEK_SETTLE_MS)
                                remoteManager.seekTo(startPositionMs)
                            }
                        }
                        ConfirmVerdict.OTHER_ITEM, ConfirmVerdict.NO_DEVICE -> emptyStreak = 0
                    }
                }
                if (superseded()) { abandon("while confirming"); return }
            } else {
                // Definitive: the body never landed. Sync the mirror with whatever the SDK has.
                playerStateManager.fetchOnce()
            }
            if (superseded()) { abandon("at the outcome"); return }
            Log.d(TAG, "wake: outcome " + when {
                confirmed   -> "confirmed — $uri playing after ${System.currentTimeMillis() - sdkStartedAt} ms"
                accepted    -> "unconfirmed — body accepted, $uri not reported playing within ${WAKE_CONFIRM_TIMEOUT_MS} ms"
                abandoned   -> "abandoned (rate limited) — the SDK plays the single item"
                bodyDropped -> "body dropped — the local app was paused or moved on before it went out"
                neverListed -> "device never listed — the SDK plays the single item"
                else        -> "failed — the SDK plays the single item"
            })
            clearIsWakingUp()
            // A 429 abandon leaves the debt owed (shuffleOwedOn): a PUT now would only be refused,
            // and the next play settles it.
            if (reassertShuffle) {
                if (accepted) reassertShuffleOn(owner) else if (!abandoned) restoreShuffleOn(owner)
            }
        } finally {
            ownedWakeRestores--
            playerStateManager.releasePendingItem(generation)
            // A CANCELLED restore (a newer play cancelled the job mid-suspend) never reaches its
            // own clear; if it still owns the waking state — no newer play took it — clear it here,
            // or the spinner is stranded (device pass 2026-09-25, C11).
            if (wakingOwner == owner) clearIsWakingUp()
        }
    }

    /** One `me/player/play` for [body], targeted at [deviceId] (null = the active device). */
    private suspend fun sendWakeBody(
        body        : WakeRestoreBody,
        uri         : String,
        positionMs  : Long?,
        sdkStartedAt: Long,
        deviceId    : String?,
        /** The local app's own position (a track body after its audible report) — wins over base + elapsed. */
        positionOverrideMs: Long? = null,
    ): Result<Unit> {
        val elapsedMs = System.currentTimeMillis() - sdkStartedAt
        val trackPositionMs = positionOverrideMs ?: positionMs?.plus(elapsedMs)
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
                contextUri     = body.contextUri,
                offsetUri      = if (body.offsetPosition == null) uri else null,
                offsetPosition = body.offsetPosition,
                positionMs     = trackPositionMs,
                deviceId       = deviceId,
            )
            is WakeRestoreBody.Uris -> {
                val leadIsEpisode = body.uris.firstOrNull()?.startsWith("spotify:episode:") == true
                repository.play(
                    uris       = body.uris,
                    positionMs = if (leadIsEpisode) null else trackPositionMs,
                    deviceId   = deviceId,
                )
            }
        }
    }

    private fun WakeRestoreBody.describe(): String = when (this) {
        is WakeRestoreBody.Context -> "context ${contextUri}" + (offsetPosition?.let { " @$it" } ?: "")
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
                e.isRateLimited() -> playerStateManager.noteRateLimited(e)
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
    private suspend fun runPlayButtonRestore(track: SpotifyTrack, pausedProgressMs: Long, step: Int): Boolean {
        if (!viewModelScope.isActive) return false
        val job = withContext(Dispatchers.Main) {
            val owner = ++wakingOwner
            playbackJob?.cancel()
            val generation = playerStateManager.playGeneration
            viewModelScope.async { restoreForResume(track, pausedProgressMs, step, generation, owner) }
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

    /**
     * The play button ([step] 0) and next / previous ([step] ±1) after Spotify died. A queue
     * whose order Lyra knows (Liked — the collection context or the liked window — or a uris
     * origin) and the user's shuffle OFF: the restore lands straight on the NEIGHBOUR — no detour
     * through the current song. Otherwise (any other context, whose order Spotify owns; shuffle
     * on, where Spotify must pick; or no neighbour) it lands on the current item and then skips,
     * which means a moment of the current song first.
     */
    private suspend fun restoreForResume(
        track           : SpotifyTrack,
        pausedProgressMs: Long,
        step            : Int,
        generation      : Long,
        owner           : Long,
    ) {
        val currentUri = track.uri
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
        val owedOn = playerStateManager.shuffleOwedOn
        val userShuffleOn = mirror.shuffleEnabled || owedOn
        // Liked Songs restores as the COLLECTION context when the user id is cached (rule 2 of
        // planWakeRestore; Cris, 2026-10-04 evening — the body Spotify's own clients send, see
        // playFromLikedSongs), else as the liked window. Read only when the plan can use it: it
        // reads the library file. D' (Cris, 2026-10-04) governs the OTHER uris bodies (a Search /
        // Stats list, an episode feed): the 4.1.0 URI list, so a user on an unaffected Spotify
        // keeps the queue after a wake; a context replaces it only while uris bodies are SUSPECT
        // (below, after the neighbour step and before the bracket).
        val collection: String? = if (planUsesLiked) loadCollectionUri() else null
        var body = planWakeRestore(currentUri, ctx, origin, likedUris, isEpisode, collection)
        // A skip on a queue whose order Lyra knows, with shuffle off: the neighbour in the FULL
        // list the plan drew from, then the plan again from there (its album is unknown: a body
        // for it falls back through the album lookup if Spotify reports it EMPTY). The collection
        // counts — its order IS the liked list's (newest first), and re-planning from the
        // neighbour gives the collection again with `offset.uri` = the neighbour, so a skip on a
        // dead Spotify still lands straight on it, as the liked window did.
        var uri = currentUri
        var thenSkip = step
        val knownOrderBody = when (val b = body) {
            is WakeRestoreBody.Uris    -> true
            is WakeRestoreBody.Context -> isCollectionContext(b.contextUri) && b.offsetPosition == null
        }
        if (step != 0 && !userShuffleOn && knownOrderBody && !isEpisode) {
            val fullList = when {
                body is WakeRestoreBody.Context -> likedUris
                origin is PlaybackOrigin.Uris && origin.uris.contains(currentUri) -> origin.uris
                else -> likedUris
            }
            val idx = fullList?.indexOf(currentUri) ?: -1
            val neighbour = if (idx >= 0) fullList?.getOrNull(idx + step) else null
            if (neighbour != null) {
                uri = neighbour
                body = planWakeRestore(neighbour, ctx, origin, likedUris, isEpisode, collection)
                thenSkip = 0
            }
        }
        // The confirm step's fallback chain (collection for a Liked origin, then the album). The
        // album is the TRACK's: a neighbour's is unknown and is looked up only on an EMPTY player.
        val album = track.albumContextUri()
        val fallbacks = PlayFallbacks(uri, origin, if (uri == currentUri) album else null, isEpisode)
        // After the neighbour step (a skip keeps its direct landing) and before the bracket (a
        // substituted collection is bracketed like any other).
        body = wakeBodyWhileSuspect(body, fallbacks)
        (body as? WakeRestoreBody.Context)?.let { fallbacks.markSent(it.contextUri) }
        val onNeighbour = uri != currentUri
        // The same bracket as a tap: a multi-uri body with shuffle on would start at random.
        val bracket = userShuffleOn && when (val b = body) {
            is WakeRestoreBody.Uris    -> b.uris.size > 1
            // see startPlay; COLLECTION_BRACKET_ENABLED = false sends it unbracketed
            is WakeRestoreBody.Context -> COLLECTION_BRACKET_ENABLED && isCollectionContext(b.contextUri)
        }
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
        val what = when (step) { 0 -> "play button"; 1 -> "next"; else -> "previous" }
        Log.d(TAG, "wake: $what — plan ${body.describe()} (mirror context=$ctx, origin=$originLabel, " +
                   "liked cache=${likedUris?.size}, shuffleBracket=$bracket, " +
                   (if (onNeighbour) "on the neighbour $uri" else "then skip $thenSkip") + ")")
        restoreAfterWake(
            uri             = uri,
            body            = body,
            positionMs      = if (isEpisode || onNeighbour) null else pausedProgressMs,
            shuffle         = sdkShuffle,
            startPositionMs = if (onNeighbour) null else pausedProgressMs,
            reassertShuffle = userShuffleOn,
            generation      = generation,
            owner           = owner,
            degradeTo       = if (isEpisode) listOf(uri) else null,
            thenSkip        = thenSkip,
            fallbacks       = fallbacks,
        )
    }

    /** The hero Shuffle button and iLyra's Shuffle Songs: [playContext] with shuffle ON. */
    fun shuffleContext(contextUri: String, itemCount: Int? = null) =
        playContext(contextUri, shuffle = true, itemCount = itemCount)

    /**
     * Play a context (playlist / album / the Liked `collection`) with [shuffle] set the way the
     * user asked — the hero PLAY (shuffle OFF, from the first item; Library, Album — since
     * 2026-09-25 pm it no longer inherits whatever shuffle state Spotify was in) and the hero
     * SHUFFLE (shuffle ON, from a random item). THE one context-play path.
     *
     * Awake: the shuffle state is awaited AND CONFIRMED by re-reading `me/player` before the play
     * goes out ("order of execution is not guaranteed" across player endpoints — with shuffle ON
     * still applied a Play started at a random song, with it OFF still pending a Shuffle started in
     * order), then the play, then one re-assert 1.5 s later if the poll says it did not stick.
     * With shuffle ON and [itemCount] known, the play carries a RANDOM raw `offset.position`
     * (device pass 2026-09-25 B7 rerun: with shuffle confirmed ON the context still started at
     * track 1 about half the time — Spotify applies the play, drops shuffle, and the re-assert only
     * shuffles the REST — so the start is chosen here, as Home Assistant's Spotify integration
     * does). The Liked `collection` too, since 2026-10-04 evening (it accepts `offset.position`, lab
     * 2026-09-25): sent with NO offset, Spotify restarted or resumed the CURRENT item instead —
     * repeated hero Shuffles replayed the same song and Play just resumed (device evidence 20:43) —
     * so its callers pass the liked TOTAL as [itemCount], never a row count.
     *
     * Cold (404): the App Remote applies the shuffle state, then plays the CONTEXT itself (proven:
     * the whole context loads — device pass 2026-09-25 B7 / IPOD #8). The player shows WAKING
     * until Spotify reports playing (Cris's rule), then shuffle is re-asserted through the Web API
     * if the poll says it did not stick. The SDK takes no offset, so a cold Shuffle can still open
     * on track 1. Before 2026-09-25 the Library's own `shufflePlaylist` never set shuffle on the
     * SDK at all, so a cold Shuffle tap played in order and needed a second tap.
     *
     * Rate-limit-gated throughout; a newer play cancels it (it is the current [playbackJob]).
     */
    fun playContext(contextUri: String, shuffle: Boolean, itemCount: Int? = null) {
        if (playerStateManager.isRateLimited()) {
            // Never a silent no-op: the hero buttons "did nothing" for a whole evening (2026-09-25).
            val left = playerStateManager.rateLimitSecondsLeft()
            Log.w(TAG, "playContext refused: rate limited for another $left s")
            _uiState.update { it.copy(error = "Spotify is rate limiting Lyra — try again in $left s") }
            return
        }
        playerStateManager.recordPlayOrigin(PlaybackOrigin.forContext(contextUri))
        _uiState.update { it.copy(playUnconfirmed = false, spotifyNotListed = false) }
        playerStateManager.clearShuffleOwed()   // an explicit shuffle choice settles a bracket's debt
        playerStateManager.setOptimisticallyPlaying()
        playerStateManager.resetProgressForNewTrack()
        val owner = ++wakingOwner
        playbackJob?.cancel()
        val generation = playerStateManager.playGeneration
        playbackJob = viewModelScope.launch {
            // A play here took the waking state over (`++wakingOwner` above) — and cancelled the job
            // that would have cleared it: the awake path never clears it on its own, so without this
            // a spinner inherited from a tap's confirm stayed up for good (review 2026-10-04).
            try {
                val preSet = shuffle
                val shuffleResult = playerStateManager.applyShuffle(preSet)
                val shuffleErr = shuffleResult.exceptionOrNull()
                if (shuffleErr != null && shuffleErr.isRateLimited()) {
                    playerStateManager.noteRateLimited(shuffleErr)
                    playerStateManager.releasePlayingOptimism()
                    return@launch
                }
                val shuffleWas404 = shuffleErr != null && shuffleErr.isNoActiveDevice()
                // The state must have TAKEN EFFECT before the context play: OFF still pending → a
                // random first song; ON still pending → track 1 with the rest in order.
                if (shuffleErr == null) confirmShuffleState(preSet)
                // An explicit offset is honoured WHATEVER the shuffle state (device pass 2026-09-25,
                // A5), and the state the poll confirmed is not what decides the first song: the phone
                // client applies its own remembered preference when a context starts (pm rerun: "OFF
                // confirmed", then "landed with shuffle=true", every time until the re-asserts had
                // flipped it). So the first item is pinned here — position 0 for Play, a random
                // position for Shuffle — and the re-assert below only has to sort out the REST.
                // The Liked `collection` the same way since 2026-10-04 evening: it accepts
                // `offset.position` (lab 2026-09-25 pm), and with NO offset Spotify restarted or
                // resumed the CURRENT item — repeated hero Shuffles replayed one song, Play resumed.
                // [itemCount] is the raw TOTAL (a playlist's / the liked library's), so a random
                // position is always a real slot. Cold (below) the SDK takes no offset either way.
                val startAt = when {
                    !shuffle -> 0
                    else     -> itemCount?.takeIf { it > 1 }?.let { Random.nextInt(it) }
                }
                Log.d(TAG, "context play: $contextUri shuffle=$shuffle offset.position=$startAt (itemCount=$itemCount)")

                repository.play(contextUri = contextUri, offsetPosition = startAt).fold(
                    onSuccess = {
                        // Re-assert: Spotify sometimes applies the play before the shuffle (or drops
                        // shuffle as a context starts). Clear the optimistic lock so fetchOnce reads
                        // the server's truth, then re-set it if it didn't stick.
                        delay(SHUFFLE_REASSERT_DELAY_MS)
                        playerStateManager.clearShuffleLock()
                        playerStateManager.fetchOnce()
                        if (playerStateManager.state.value.shuffleEnabled != shuffle) {
                            Log.d(TAG, "shuffle: context play landed with shuffle=${!shuffle}; re-asserting $shuffle")
                            repository.setShuffle(shuffle)
                                .onFailure { if (it.isRateLimited()) playerStateManager.noteRateLimited(it) }
                        }
                    },
                    onFailure = { e ->
                        if (e.isNoActiveDevice() || shuffleWas404) {
                            run {
                                playContextViaRemote(contextUri, shuffle, owner, generation)
                            }
                        } else {
                            if (e.isRateLimited()) playerStateManager.noteRateLimited(e)
                            playerStateManager.releasePlayingOptimism()
                            // Never a silent no-op (audit 2026-10-04 W6): a 403, a 5xx or no network
                            // used to flash "playing" and revert with no word.
                            _uiState.update { it.copy(error = e.message) }
                        }
                    },
                )
            } finally {
                if (wakingOwner == owner) clearIsWakingUp()
            }
        }
    }

    /**
     * The cold half of [playContext]: connect, set [shuffle] on the SDK, play the context on the
     * SDK, hold the waking state until `me/player` reports playing (up to [WAKE_DEVICE_TIMEOUT_MS]),
     * then re-assert the shuffle state if it did not stick.
     */
    private suspend fun playContextViaRemote(contextUri: String, shuffle: Boolean, owner: Long, generation: Long) {
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
                Log.d(TAG, "wake: context play — App Remote could not connect")
                playerStateManager.releasePlayingOptimism()
                _uiState.update { it.copy(error = "Couldn't connect to Spotify", isPlaying = false) }
                return
            }
            remoteManager.setShuffle(shuffle)
            delay(REMOTE_SHUFFLE_SETTLE_MS)
            remoteManager.play(contextUri)
            val startedAt = System.currentTimeMillis()
            Log.d(TAG, "wake: SDK shuffle=$shuffle + context play dispatched ($contextUri)")
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
            Log.d(TAG, "wake: context play outcome " + when (observedShuffle) {
                null    -> "unconfirmed — not reported playing within ${WAKE_DEVICE_TIMEOUT_MS} ms"
                shuffle -> "confirmed (shuffle=$shuffle) after ${System.currentTimeMillis() - startedAt} ms"
                else    -> "playing with shuffle=${!shuffle} after ${System.currentTimeMillis() - startedAt} ms; re-asserting $shuffle"
            })
            // The same quiet line a tap's ceiling shows (audit W6): the timeout used to be a log line only.
            if (observedShuffle == null && !superseded()) {
                _uiState.update { it.copy(playUnconfirmed = true, isPlaying = false) }
            }
            if (observedShuffle != null && observedShuffle != shuffle && !playerStateManager.isRateLimited()) {
                playerStateManager.clearShuffleLock()
                repository.setShuffle(shuffle)
                    .onFailure { if (it.isRateLimited()) playerStateManager.noteRateLimited(it) }
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
