package com.crsmthw.lyra.data.player

import android.content.Context
import android.content.Intent
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
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

data class PlayerState(
    val isPlaying             : Boolean        = false,
    val currentTrack          : SpotifyTrack?  = null,
    val progressMs            : Long           = 0L,
    val durationMs            : Long           = 0L,
    val shuffleEnabled        : Boolean        = false,
    val repeatState           : String         = "off",
    val sleepTimerMinutes     : Int            = 0,
    val sleepTimerTotalMinutes: Int            = 0,
    val currentDevice         : SpotifyDevice? = null,
)

class PlayerStateManager(
    private val context      : Context,
    private val repository   : SpotifyRepository,
    private val remoteManager: SpotifyRemoteManager,
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

    suspend fun fetchOnce() = fetchPlayerState()

    private suspend fun fetchPlayerState() {
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
                            currentDevice  = response.device,
                        )
                    }
                    val isNowPlaying = _state.value.isPlaying
                    if (isNowPlaying && progressTickJob?.isActive != true) startProgressTick()
                    else if (!isNowPlaying) progressTickJob?.cancel()
                    maybeStartService()
                } else {
                    // 204 — no active device (Spotify killed or closed)
                    // Clear playing state unless an optimistic lock is in effect (e.g. SDK wake-up in progress)
                    val now = System.currentTimeMillis()
                    if (now >= isPlayingLockUntil) {
                        _state.update { it.copy(isPlaying = false) }
                        progressTickJob?.cancel()
                    }
                }
            },
            onFailure = { e ->
                when {
                    e.message?.contains("429") == true ->
                        pollBackoffUntil = System.currentTimeMillis() + 60_000L
                    e.isTransientNetworkError() -> { /* silent */ }
                }
            },
        )
    }

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
                            _state.update { it.copy(progressMs = 0L) }
                            remoteManager.connectAndPlay(track.uri)
                            delay(500L)
                            fetchPlayerState()
                            // Start tick optimistically so the bar counts from the moment
                            // indeterminate clears, even if fetchPlayerState returned 204.
                            if (progressTickJob?.isActive != true) startProgressTick()
                            onWakeOperationComplete?.invoke()
                        }
                    },
                )
            }
        }
    }

    fun skipNext() {
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
        val new = !_state.value.shuffleEnabled
        lockShuffle()
        _state.update { it.copy(shuffleEnabled = new) }
        scope.launch {
            repository.setShuffle(new).onFailure { e ->
                if (e.message?.contains("404") == true) remoteManager.setShuffle(new)
            }
        }
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
