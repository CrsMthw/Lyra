package com.crsmthw.lyra.data.player

import android.content.Context
import android.content.Intent
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
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
    val isPlaying             : Boolean       = false,
    val currentTrack          : SpotifyTrack? = null,
    val progressMs            : Long          = 0L,
    val durationMs            : Long          = 0L,
    val shuffleEnabled        : Boolean       = false,
    val repeatState           : String        = "off",
    val sleepTimerMinutes     : Int           = 0,
    val sleepTimerTotalMinutes: Int           = 0,
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

    @Volatile private var serviceRunning = false

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
                    _state.update {
                        it.copy(
                            isPlaying      = if (now < isPlayingLockUntil) it.isPlaying else response.isPlaying,
                            currentTrack   = response.item,
                            progressMs     = response.progressMs,
                            durationMs     = response.item?.durationMs ?: 0L,
                            shuffleEnabled = if (now < shuffleLockUntil) it.shuffleEnabled else response.shuffleState,
                            repeatState    = if (now < repeatLockUntil) it.repeatState else response.repeatState,
                        )
                    }
                    val isNowPlaying = _state.value.isPlaying
                    if (isNowPlaying && progressTickJob?.isActive != true) startProgressTick()
                    else if (!isNowPlaying) progressTickJob?.cancel()
                    maybeStartService()
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

    fun lockIsPlaying() { isPlayingLockUntil = System.currentTimeMillis() + 5_000L }
    fun lockShuffle()   { shuffleLockUntil   = System.currentTimeMillis() + 5_000L }
    fun lockRepeat()    { repeatLockUntil    = System.currentTimeMillis() + 5_000L }

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
                _state.update { it.copy(isPlaying = true) }
                startProgressTick()
                maybeStartService()
                remoteManager.resume()
                repository.play()
            }
        }
    }

    fun skipNext() {
        scope.launch {
            repository.skipNext().fold(
                onSuccess = { delay(500L); fetchPlayerState() },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        remoteManager.skipNext(); delay(500L); fetchPlayerState()
                    }
                },
            )
        }
    }

    fun skipPrevious() {
        scope.launch {
            repository.skipPrevious().fold(
                onSuccess = { delay(500L); fetchPlayerState() },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        remoteManager.skipPrevious(); delay(500L); fetchPlayerState()
                    }
                },
            )
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
            remoteManager.setShuffle(new)
            repository.setShuffle(new)
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
            remoteManager.setRepeat(sdkMode)
            repository.setRepeat(next)
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
