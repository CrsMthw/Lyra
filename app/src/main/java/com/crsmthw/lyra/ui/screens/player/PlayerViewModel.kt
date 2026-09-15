package com.crsmthw.lyra.ui.screens.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.LyricsRepository
import com.crsmthw.lyra.data.repository.LyricsState
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.ui.components.TrackActionsController
import com.crsmthw.lyra.ui.components.toTrackActionTarget
import com.crsmthw.lyra.util.LyricLine
import com.crsmthw.lyra.util.visualizer.VisualizerManager
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    /** Shared add-to-playlist implementation (the same one the song touch-and-hold menu uses),
     *  targeting the current track. The picker methods below are thin delegations to it. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)
    val pickerState: StateFlow<PlaylistPickerState> get() = trackActions.pickerState

    // Fallback timer used when the wake path goes through LibraryViewModel (no callback available).
    // playTrack / playPause / skip paths cancel this job and clear explicitly via clearIsWakingUp().
    private var clearWakingUpJob: Job? = null
    private var lyricsJob: Job? = null

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
                } else {
                    // Fallback: library-play (LibraryViewModel) calls the SDK with no callback.
                    // For playTrack/playPause/skip the explicit clear will cancel this before it fires.
                    clearWakingUpJob = viewModelScope.launch {
                        delay(3_500L)
                        _uiState.update { it.copy(isWakingUp = false) }
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
        remoteManager.connect(onConnected = { }, onFailure = { })
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

    fun playFromLikedSongs(trackUri: String) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                // distinctBy { it.id }: guard the play queue against a not-yet-healed cache that may
                // still hold duplicate liked songs (see LibraryViewModel pagination dedup).
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.distinctBy { it.id }
            }
            if (cached != null) {
                val idx = cached.indexOfFirst { it.uri == trackUri }.coerceAtLeast(0)
                playTrack(trackUri, uris = cached.drop(idx).map { it.uri }.take(750))
            } else {
                playTrack(trackUri)
            }
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
     */
    fun playTrack(
        uri            : String,
        contextUri     : String?       = null,
        uris           : List<String>? = null,
        index          : Int?          = null,
        startPositionMs: Long?         = null,
    ) {
        val isEpisode = uri.startsWith("spotify:episode:")
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
        viewModelScope.launch {
            // The body an EPISODE degrades to, and the body it restores with — null for a track.
            // `repository.play` is wire-identical for `uris = [uri]` and `uri = uri` (both send
            // `PlayRequest(uris = listOf(uri))`), so this changes nothing about the request; what it
            // changes is that `playUris` stays non-null for an episode, which is what makes
            // `needsRestore` true below. Without it a ONE-EPISODE show (whose `uris` is null by
            // design) never ran the restore loop at all, so the App Remote's 0:00 start was never
            // corrected — the Web API's play is the only one of the two that honours Spotify's
            // server-side resume point. Gated on `contextUri == null` so the restore loop's
            // `pending` branch can never pre-empt its `contextUri` branch.
            val episodeSingleUri = listOf(uri).takeIf { isEpisode && contextUri == null }
            // The uri list actually being sent. Queue continuity is BEST-EFFORT: `uris` holding
            // more than one entry is undocumented for episodes (the Web API reference describes
            // `uris` as track uris, and a show is not a valid `context_uri`), while a SINGLE uri is
            // the shape podcasts shipped on and tracks have always used. So the first time the API
            // refuses the multi-uri body we degrade to `[uri]` once and carry on — the user loses
            // the queue, not the playback. `playUris` is a var so the SDK-restore loop below cannot
            // spend its whole 10-second window re-sending a body the API has already rejected.
            var playUris = uris ?: episodeSingleUri
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
                    delay(1_000L)
                    playerStateManager.fetchOnce()
                    clearIsWakingUp()
                },
                onFailure = { e ->
                    if (e.isNoActiveDevice()) {
                        val sdkSuccess = when {
                            contextUri != null && index != null -> {
                                val ok = remoteManager.connectAndPlay(contextUri)
                                if (ok) remoteManager.skipToIndex(contextUri, index)
                                ok
                            }
                            else -> {
                                val ok = remoteManager.connectAndPlay(uri)
                                // The SDK's play() starts an episode at 0:00 — unlike the Web API
                                // it does not consult the server-side resume point. When the caller
                                // knows the position, seek to it rather than waiting for the restore
                                // loop's first attempt. The settle delay is required, not defensive:
                                // connectAndPlay returns when the IPC call has been DISPATCHED, not
                                // when playback has started, so a seek in the same breath arrives
                                // before the item does and is dropped.
                                if (ok && startPositionMs != null) {
                                    delay(SDK_SEEK_SETTLE_MS)
                                    remoteManager.seekTo(startPositionMs)
                                }
                                ok
                            }
                        }
                        // Cancel the 3.5s fallback timer — we own the clear from here.
                        clearWakingUpJob?.cancel()
                        if (sdkSuccess) {
                            // `playUris`, not `uris`: a list already degraded above is gone, and
                            // there is then nothing to restore (the SDK is playing the single item),
                            // so the loop is correctly skipped. For an EPISODE `playUris` is never
                            // null (see `episodeSingleUri`) — the restore is what puts it at its
                            // resume point, so it must run even with no queue to rebuild.
                            val needsRestore = playUris != null || (contextUri != null && index == null)
                            if (needsRestore) {
                                val sdkStartedAt = System.currentTimeMillis()
                                val deadline = sdkStartedAt + 10_000L
                                while (System.currentTimeMillis() < deadline) {
                                    delay(1_500L)
                                    val elapsedMs = System.currentTimeMillis() - sdkStartedAt
                                    val pending   = playUris
                                    var rateLimited = false
                                    val ok = when {
                                        pending != null -> {
                                            // An EPISODE resumes from Spotify's own server-side
                                            // position when the play call carries NO `position_ms` —
                                            // which is why a half-listened episode resumes correctly
                                            // whenever the Web API is the path that starts it.
                                            // Sending `elapsedMs` overrode that and pinned the
                                            // restored episode a second or two from the START: the
                                            // symptom Cris hit was "force-stop Spotify, tap a
                                            // half-listened episode, it plays from 0:00". A track
                                            // has no resume point, so for tracks `elapsedMs` is
                                            // still exactly what makes the restore seamless.
                                            //
                                            // Read off the body's OWN first entry rather than the
                                            // tapped `uri`: `position_ms` applies to whatever `uris`
                                            // starts with, so this stays right even if a caller ever
                                            // leads with something other than the tapped item.
                                            val restorePositionMs =
                                                if (pending.firstOrNull()?.startsWith("spotify:episode:") == true) null
                                                else elapsedMs
                                            val restore = repository.play(uris = pending, positionMs = restorePositionMs)
                                            val err     = restore.exceptionOrNull()
                                            when {
                                                // A 429 says nothing about the body — the SAME body
                                                // is fine once the window passes. Arm the shared
                                                // backoff gate and ABANDON the loop with `playUris`
                                                // INTACT: retrying every 1.5s inside a penalty
                                                // window is exactly the hammering the gate exists
                                                // to stop. This does NOT recover the queue (the SDK
                                                // is playing the single item and we stop trying to
                                                // restore it); what it fixes is the wrong diagnosis
                                                // — before, a 429 read as "the API refused this
                                                // body", nulled playUris and dropped the queue
                                                // permanently on a transient throttle.
                                                err != null && err.isRateLimited() -> {
                                                    Log.w(TAG, "SDK restore rate limited; backing off " +
                                                               "and leaving the queue unrestored", err)
                                                    playerStateManager.noteRateLimited()
                                                    rateLimited = true
                                                }
                                                // The same degrade as the direct call, applied inside
                                                // the loop: a multi-uri body the API REFUSES must not
                                                // be re-sent for the full 10-second window. A 404 here
                                                // is just "the device isn't awake yet" — that is
                                                // exactly what this loop retries for, so it never
                                                // degrades.
                                                err != null && pending.size > 1 && err.isRequestRefused() -> {
                                                    Log.w(TAG, "SDK restore refused a ${pending.size}-uri " +
                                                               "body (${err.message}); dropping the queue", err)
                                                    // Same reasoning as the direct call's degrade:
                                                    // an episode falls back to the single uri (one
                                                    // more iteration can still place it at its
                                                    // resume point), a track to null.
                                                    playUris = episodeSingleUri
                                                }
                                            }
                                            restore.isSuccess
                                        }
                                        contextUri != null -> repository.play(contextUri = contextUri, offsetUri = uri, positionMs = elapsedMs).isSuccess
                                        else               -> true
                                    }
                                    if (ok || rateLimited) break
                                }
                            }
                            delay(500L)
                            playerStateManager.fetchOnce()
                        } else {
                            playerStateManager.releasePlayingOptimism()
                            _uiState.update { it.copy(error = "Couldn't connect to Spotify.", isPlaying = false) }
                        }
                    } else {
                        // Share the one 60-second penalty window with every other caller rather
                        // than letting this path fire again into an active limit (docs/SPOTIFY.md →
                        // Rate Limiting); the device-transfer and volume paths do the same.
                        if (e.isRateLimited()) playerStateManager.noteRateLimited()
                        playerStateManager.releasePlayingOptimism()
                        _uiState.update { it.copy(error = e.message, isPlaying = false) }
                    }
                    clearIsWakingUp()
                },
            )
        }
    }

    /** Play a context (album/playlist) with shuffle enabled — mirrors LibraryViewModel.shufflePlaylist. */
    fun shuffleContext(contextUri: String) {
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.setShuffle(true)
            repository.play(contextUri = contextUri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(contextUri)
                } else {
                    playerStateManager.releasePlayingOptimism()
                }
            }
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
        ) as T
}
