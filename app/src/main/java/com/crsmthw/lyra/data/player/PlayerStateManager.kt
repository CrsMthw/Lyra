package com.crsmthw.lyra.data.player

import android.content.Context
import android.content.Intent
import com.crsmthw.lyra.data.local.PlaybackOriginStore
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.PlayerStateResponse
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.service.LyraForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * After Lyra itself records a play's origin, a context change the poll reports within this window
 * is NOT recorded: a poll already in flight when the play went out still describes the PREVIOUS
 * playback and would overwrite the origin Lyra just wrote (a Liked play replaced by the playlist
 * that was playing a second earlier).
 */
private const val ORIGIN_POLL_QUIET_MS = 6_000L

data class PlayerState(
    val isPlaying             : Boolean        = false,
    val currentTrack          : SpotifyTrack?  = null,
    val progressMs            : Long           = 0L,
    val durationMs            : Long           = 0L,
    val shuffleEnabled        : Boolean        = false,
    val repeatState           : String         = "off",
    /**
     * Does the current playback have a CONTEXT (playlist / album / artist / show), as opposed to a
     * bare `uris` list? `QueueViewModel` needs it because `me/player/queue` echoes the playing item
     * back as the queue head only in the context-less case.
     *
     * Unlike [repeatState] it has no optimistic lock of its own — nothing in the app mutates it
     * locally — but it DOES follow the mid-transfer track lock: `item` and `context` go null
     * together while Spotify switches devices, and taking it raw there would read "no context" for
     * a poll window while [currentTrack] is still held at the previous track.
     */
    val hasContext            : Boolean        = false,
    /**
     * The playback context's uri (`response.context?.uri`) — what the play button's wake restore
     * rebuilds the queue from (`planWakeRestore`). Locked with the track exactly like
     * [hasContext], and — like every field but `isPlaying` — left alone by a 204, so it survives
     * Spotify dying, which is the whole point of reading it. Null for a bare `uris` play.
     */
    val contextUri            : String?        = null,
    val sleepTimerMinutes     : Int            = 0,
    val sleepTimerTotalMinutes: Int            = 0,
    val currentDevice         : SpotifyDevice? = null,
)

class PlayerStateManager(
    private val context      : Context,
    private val repository   : SpotifyRepository,
    private val remoteManager: SpotifyRemoteManager,
    private val originStore  : PlaybackOriginStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private var pollJob        : Job? = null
    private var progressTickJob: Job? = null
    private var sleepTimerJob  : Job? = null

    private var isPlayingLockUntil: Long = 0L
    private var shuffleLockUntil  : Long = 0L
    private var repeatLockUntil   : Long = 0L
    private var pollBackoffUntil  : Long = 0L
    private var trackLockUntil    : Long = 0L

    @Volatile private var serviceRunning = false

    // Fired at the START of each SDK 404 path — before connectAndPlay/skipNext/etc.
    // PlayerViewModel sets isWakingUp=true here. Fires even when connectSuspend() short-circuits
    // on a stale connection, so the indeterminate indicator always shows during position restore.
    var onWakeOperationStart: (() -> Unit)? = null

    // Called after each SDK-wake operation completes (playPause/skip 404 paths).
    // PlayerViewModel sets this to clear its isWakingUp flag precisely when the operation is done.
    var onWakeOperationComplete: (() -> Unit)? = null

    /**
     * The play button's wake restore (set by `PlayerViewModel.init`): runs the same
     * `restoreAfterWake` as a track tap, with the body `planWakeRestore` picks and the progress at
     * the tap as the base position. Returns false when it could not run at all (the ViewModel is
     * gone), in which case [playPause] falls back to the bare single-uri `connectAndPlay`.
     * It owns clearing the waking state; [onWakeOperationComplete] is not fired when it ran.
     */
    var onWakeRestore: (suspend (track: SpotifyTrack, pausedProgressMs: Long) -> Boolean)? = null

    // ── Playback origin + play-request generation ─────────────────────────────

    /**
     * Bumped by every user play/pause/skip request. A wake restore captures it when it starts and
     * abandons silently — no body, no waking-state change — once it moves: the device wait can
     * take a minute, and a body landing after the user picked something else (or paused) would
     * override them. Every Lyra-issued play also records its origin, so [recordPlayOrigin] bumps it.
     */
    private val playRequestGeneration = AtomicLong(0L)
    val playGeneration: Long get() = playRequestGeneration.get()
    fun notePlayRequest(): Long = playRequestGeneration.incrementAndGet()

    /**
     * True while a play made with the user's shuffle ON still owes its re-assert
     * (PlayerViewModel.startPlay: for a multi-uri body the bracket shuffle OFF → play → shuffle ON;
     * for a context / single body just the ON after the play — Spotify can DROP shuffle when a
     * context starts after a `uris` playback, device pass 2026-09-25 A3). The mirror then
     * reads `shuffleEnabled = false`, so without this a second tap inside the ~1.5 s window — or a
     * pause / skip / Library play superseding a wake restore — would read "shuffle is off" and the
     * user's shuffle would stay off for good. The next play treats `mirror || owed` as the user's
     * setting; cleared by a successful ON, by an explicit shuffle choice ([toggleShuffle],
     * [setShuffle], a caller's `shuffle` argument, a shuffle-play), never by a 429.
     */
    @Volatile var shuffleOwedOn: Boolean = false
        private set
    fun markShuffleOwedOn() { shuffleOwedOn = true }
    fun clearShuffleOwed()  { shuffleOwedOn = false }

    /**
     * ONE writer for the origin file: two writes launched close together (a Lyra play and a poll
     * observation) must land in the order they were issued, or the older origin would be what
     * survives on disk. `synchronized` in the store protects the file, not the order.
     */
    private val originWriter = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var lastLyraOriginAt = 0L
    /** The context uri the poll last reported (null included), so a CHANGE is what gets recorded. */
    @Volatile private var lastObservedContextUri: String? = null

    /**
     * Records where a play Lyra is about to issue comes from, and counts as a new play request.
     * The write runs on `Dispatchers.IO`; the generation bump is synchronous, so a caller that
     * reads [playGeneration] right after sees its own request.
     */
    fun recordPlayOrigin(origin: PlaybackOrigin) {
        notePlayRequest()
        lastLyraOriginAt = System.currentTimeMillis()
        scope.launch(originWriter) { originStore.save(origin) }
    }

    suspend fun loadPlayOrigin(): PlaybackOrigin? = withContext(originWriter) { originStore.load() }

    /** Poll side: remember a context the user started elsewhere (e.g. inside the Spotify app). */
    private fun observeContextUri(contextUri: String?) {
        if (contextUri == lastObservedContextUri) return
        lastObservedContextUri = contextUri
        if (contextUri == null) return
        if (System.currentTimeMillis() - lastLyraOriginAt < ORIGIN_POLL_QUIET_MS) return
        val origin = PlaybackOrigin.forContext(contextUri)
        scope.launch(originWriter) { originStore.save(origin) }
    }

    init { startPolling() }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= pollBackoffUntil) fetchPlayerState()
                delay(3_000L)
            }
        }
    }

    /**
     * One poll now. Returns the RAW response (null on a 204 or a failure): the mirror's
     * `isPlaying` is held by the optimistic lock, so a caller that must know what Spotify REALLY
     * reports — the wake restore's "is the song playing yet" check — reads this instead.
     */
    suspend fun fetchOnce(): PlayerStateResponse? = fetchPlayerState()

    private suspend fun fetchPlayerState(): PlayerStateResponse? =
        repository.getPlayerState().fold(
            onSuccess = { response ->
                if (response != null) {
                    val now = System.currentTimeMillis()
                    // During a device transfer the API briefly returns a null item (mid-transition).
                    // Lock track+progress+duration together so the UI doesn't flash "Nothing Playing"
                    // or reset the seek bar to 0 while Spotify is switching devices.
                    val lockingTransfer = now < trackLockUntil && response.item == null
                    _state.update {
                        it.copy(
                            isPlaying      = if (now < isPlayingLockUntil) it.isPlaying else response.isPlaying,
                            currentTrack   = if (lockingTransfer) it.currentTrack else response.item,
                            progressMs     = if (lockingTransfer) it.progressMs else response.progressMs,
                            durationMs     = if (lockingTransfer) it.durationMs else (response.item?.durationMs ?: it.durationMs),
                            shuffleEnabled = if (now < shuffleLockUntil) it.shuffleEnabled else response.shuffleState,
                            repeatState    = if (now < repeatLockUntil) it.repeatState else response.repeatState,
                            // Locked with the TRACK, not on a lock of its own: `context` goes null
                            // alongside `item` mid-transfer, and the queue's echo drop reads this.
                            hasContext     = if (lockingTransfer) it.hasContext else response.context != null,
                            contextUri     = if (lockingTransfer) it.contextUri
                                             else response.context?.uri?.takeIf { u -> u.isNotBlank() },
                            currentDevice  = response.device,
                        )
                    }
                    if (!lockingTransfer) observeContextUri(_state.value.contextUri)
                    val isNowPlaying = _state.value.isPlaying
                    if (isNowPlaying && progressTickJob?.isActive != true) startProgressTick()
                    else if (!isNowPlaying) progressTickJob?.cancel()
                    maybeStartService()
                } else {
                    // 204 — no active device (Spotify killed or closed)
                    // Clear playing state unless an optimistic lock is in effect (e.g. SDK wake-up in progress).
                    // ONLY isPlaying: the track and its contextUri must survive, the play button
                    // restores from them.
                    val now = System.currentTimeMillis()
                    if (now >= isPlayingLockUntil) {
                        _state.update { it.copy(isPlaying = false) }
                        progressTickJob?.cancel()
                    }
                }
                response
            },
            onFailure = { e ->
                when {
                    e.message?.contains("429") == true ->
                        pollBackoffUntil = System.currentTimeMillis() + 60_000L
                    e.isTransientNetworkError() -> { /* silent */ }
                }
                null
            },
        )

    // ── Progress tick ─────────────────────────────────────────────────────────

    private fun startProgressTick() {
        progressTickJob?.cancel()
        progressTickJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                _state.update { s ->
                    s.copy(progressMs = (s.progressMs + 1_000L).coerceAtMost(s.durationMs))
                }
            }
        }
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private fun maybeStartService() {
        if (!serviceRunning && (_state.value.isPlaying || _state.value.sleepTimerMinutes > 0)) {
            try {
                context.startForegroundService(Intent(context, LyraForegroundService::class.java))
                serviceRunning = true
            } catch (_: Exception) { }
        }
    }

    fun notifyServiceStopped() { serviceRunning = false }

    // ── Optimistic locks ──────────────────────────────────────────────────────

    fun lockIsPlaying()  { isPlayingLockUntil = System.currentTimeMillis() + 5_000L }
    fun lockShuffle()    { shuffleLockUntil   = System.currentTimeMillis() + 5_000L }
    fun lockRepeat()     { repeatLockUntil    = System.currentTimeMillis() + 5_000L }
    // Prevents currentTrack from being nulled by a mid-transfer poll where response.item is briefly null.
    fun lockTrack()      { trackLockUntil     = System.currentTimeMillis() + 3_000L }
    fun isRateLimited()  = System.currentTimeMillis() < pollBackoffUntil
    fun noteRateLimited() { pollBackoffUntil  = System.currentTimeMillis() + 60_000L }

    // Optimistically marks Spotify as playing AND locks the state so transient 204 polls
    // during SDK wake-up don't flip the UI back to the play icon.
    // Resets progress to 0 and stops the tick so the bar doesn't count up from the old position
    // while the new track is loading. Call immediately after setOptimisticallyPlaying().
    fun resetProgressForNewTrack() {
        progressTickJob?.cancel()
        _state.update { it.copy(progressMs = 0L) }
    }

    // Starts the progress tick if isPlaying=true and the tick isn't already running.
    // Call just before clearing isWakingUp so the bar starts counting the moment it becomes determinate.
    fun ensureTickRunning() {
        if (_state.value.isPlaying && progressTickJob?.isActive != true) startProgressTick()
    }

    fun setOptimisticallyPlaying() {
        isPlayingLockUntil = System.currentTimeMillis() + 5_000L
        _state.update { it.copy(isPlaying = true) }
        if (progressTickJob?.isActive != true) startProgressTick()
        maybeStartService()
    }

    // Releases the optimistic lock and marks playback as stopped.
    // Call on failure paths where the wake attempt failed entirely.
    fun releasePlayingOptimism() {
        isPlayingLockUntil = 0L
        _state.update { it.copy(isPlaying = false) }
        progressTickJob?.cancel()
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    fun playPause() {
        val current = _state.value
        notePlayRequest()
        lockIsPlaying()
        scope.launch {
            if (current.isPlaying) {
                _state.update { it.copy(isPlaying = false) }
                progressTickJob?.cancel()
                remoteManager.pause()
                repository.pause()
            } else {
                val track = current.currentTrack
                _state.update { it.copy(isPlaying = true) }
                startProgressTick()
                maybeStartService()
                repository.play().fold(
                    onSuccess = { delay(500L); fetchPlayerState() },
                    onFailure = { e ->
                        if (e.message?.contains("404") == true && track != null) {
                            onWakeOperationStart?.invoke()
                            progressTickJob?.cancel()
                            // The full restore (PlayerViewModel.restoreAfterWake): the SDK plays the
                            // item, the queue is rebuilt from where it came from, and the waking
                            // state clears only once Spotify reports the song playing — the hook
                            // owns that clear. Before 2026-09-25 this was the bare single-uri play
                            // below and nothing else, so a play after Spotify died queued ONE song.
                            val restored = onWakeRestore?.invoke(track, current.progressMs) == true
                            if (!restored) {
                                _state.update { it.copy(progressMs = 0L) }
                                remoteManager.connectAndPlay(track.uri)
                                delay(500L)
                                fetchPlayerState()
                                // Start tick optimistically so the bar counts from the moment
                                // indeterminate clears, even if fetchPlayerState returned 204.
                                if (progressTickJob?.isActive != true) startProgressTick()
                                onWakeOperationComplete?.invoke()
                            }
                        }
                    },
                )
            }
        }
    }

    fun skipNext() {
        notePlayRequest()
        scope.launch {
            setOptimisticallyPlaying()
            val prevId = _state.value.currentTrack?.id
            resetProgressForNewTrack()
            repository.skipNext().fold(
                onSuccess = {
                    fetchUntilTrackChanges(prevId)
                    ensureTickRunning()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        onWakeOperationStart?.invoke()
                        remoteManager.skipNext()
                        fetchUntilTrackChanges(prevId)
                        onWakeOperationComplete?.invoke()
                    } else {
                        releasePlayingOptimism()
                    }
                },
            )
        }
    }

    fun skipPrevious() {
        notePlayRequest()
        scope.launch {
            setOptimisticallyPlaying()
            val prevId = _state.value.currentTrack?.id
            resetProgressForNewTrack()
            repository.skipPrevious().fold(
                onSuccess = {
                    fetchUntilTrackChanges(prevId)
                    ensureTickRunning()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        onWakeOperationStart?.invoke()
                        remoteManager.skipPrevious()
                        fetchUntilTrackChanges(prevId)
                        onWakeOperationComplete?.invoke()
                    } else {
                        releasePlayingOptimism()
                    }
                },
            )
        }
    }

    // Polls until currentTrack.id changes from prevTrackId (up to maxMs), checking every 700 ms.
    private suspend fun fetchUntilTrackChanges(prevTrackId: String?, maxMs: Long = 4_000L) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            delay(700L)
            fetchPlayerState()
            if (_state.value.currentTrack?.id != prevTrackId) return
        }
    }

    fun seekTo(fraction: Float) {
        val posMs = (fraction * _state.value.durationMs).toLong()
        _state.update { it.copy(progressMs = posMs) }
        scope.launch { repository.seek(posMs) }
    }

    fun toggleShuffle() {
        clearShuffleOwed()
        val new = !_state.value.shuffleEnabled
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = new) }
        scope.launch {
            repository.setShuffle(new).onFailure { e ->
                if (e.message?.contains("404") == true) remoteManager.setShuffle(new)
            }
        }
    }

    /**
     * Sets shuffle to [enabled] without toggling — the Classic's deliberate-selection path (shuffle OFF
     * before playing the tapped song) and Shuffle Songs (shuffle ON). Mirrors [toggleShuffle]'s
     * structure: optimistic lock + optimistic state + Web API, 404 → App Remote.
     */
    fun setShuffle(enabled: Boolean) {
        clearShuffleOwed()
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        scope.launch {
            repository.setShuffle(enabled).onFailure { e ->
                if (e.message?.contains("404") == true) remoteManager.setShuffle(enabled)
            }
        }
    }

    /**
     * Awaitable variant of [setShuffle] for callers that need to sequence the shuffle change BEFORE
     * a play request (iLyra mode: shuffle OFF → play the tapped song, otherwise the uris body starts
     * at a random entry). Returns the Web API result; a 404 is NOT swallowed — the caller owns the
     * App Remote fallback and must apply shuffle there too.
     */
    suspend fun applyShuffle(enabled: Boolean): Result<Unit> {
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        return repository.setShuffle(enabled).also { if (enabled && it.isSuccess) clearShuffleOwed() }
    }

    /**
     * Clears the optimistic shuffle lock so the next [fetchPlayerState] writes the server's truth.
     * Used by the shuffle-re-assert path in [PlayerViewModel.shuffleContext]: the 1.5 s verification
     * fetch must read the REAL server state, not the locked-true optimistic value.
     */
    fun clearShuffleLock() {
        shuffleLockUntil = 0L
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeatState) {
            "context" -> "track"
            "track"   -> "off"
            else      -> "context"
        }
        lockRepeat()
        _state.update { it.copy(repeatState = next) }
        val sdkMode = when (next) { "context" -> 1; "track" -> 2; else -> 0 }
        scope.launch {
            repository.setRepeat(next).onFailure { e ->
                if (e.message?.contains("404") == true) remoteManager.setRepeat(sdkMode)
            }
        }
    }

    /** Sets repeat to an explicit state ("off" / "context" / "track") — the Classic's repeat bar. */
    fun setRepeat(state: String) {
        lockRepeat()
        _state.update { it.copy(repeatState = state) }
        val sdkMode = when (state) { "context" -> 1; "track" -> 2; else -> 0 }
        scope.launch {
            repository.setRepeat(state).onFailure { e ->
                if (e.message?.contains("404") == true) remoteManager.setRepeat(sdkMode)
            }
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepTimerTotalMinutes = minutes) }
        if (minutes <= 0) return
        maybeStartService()
        sleepTimerJob = scope.launch {
            repeat(minutes) { elapsed ->
                delay(60_000L)
                val remaining = minutes - elapsed - 1
                _state.update { it.copy(sleepTimerMinutes = remaining) }
                if (remaining == 0) {
                    remoteManager.pause()
                    repository.pause()
                    _state.update { it.copy(isPlaying = false, sleepTimerTotalMinutes = 0) }
                    progressTickJob?.cancel()
                }
            }
        }
    }
}

private fun Throwable.isTransientNetworkError(): Boolean =
    cause is java.net.UnknownHostException ||
    cause is java.net.SocketException ||
    message?.contains("Unable to resolve host") == true ||
    message?.contains("Failed to connect") == true
