package com.crsmthw.lyra.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, CONTEXT, TRACK }

data class PlayerUiState(
    val isPlaying             : Boolean      = false,
    val currentTrack          : SpotifyTrack? = null,
    val progressMs            : Long         = 0L,
    val durationMs            : Long         = 0L,
    val shuffleEnabled        : Boolean      = false,
    val repeatMode            : RepeatMode   = RepeatMode.OFF,
    val isLiked               : Boolean      = false,
    val sleepTimerMinutes     : Int          = 0,
    val sleepTimerTotalMinutes: Int          = 0,
    val isLoading             : Boolean      = false,
    val error                 : String?      = null,
) {
    val progress: Float
        get() = if (durationMs > 0L) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(
    private val repository   : SpotifyRepository,
    private val remoteManager: SpotifyRemoteManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private var pollJob        : Job? = null
    private var sleepTimerJob  : Job? = null
    private var progressTickJob: Job? = null

    // Prevent polls from overwriting optimistic UI updates for 5s after user action
    private var isPlayingLockUntil: Long = 0L
    private var shuffleLockUntil  : Long = 0L
    private var repeatLockUntil   : Long = 0L

    // Back off for 30s when Spotify rate-limits us — keeps resetting the window otherwise
    private var pollBackoffUntil: Long = 0L

    init {
        startPolling()
        remoteManager.connect(
            onConnected = { },
            onFailure   = { },
        )
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= pollBackoffUntil) {
                    fetchPlayerState()
                }
                delay(3_000L)
            }
        }
    }

    private suspend fun fetchPlayerState() {
        repository.getPlayerState().fold(
            onSuccess = { state ->
                if (state != null) {
                    val newTrack  = state.item
                    val prevTrack = _uiState.value.currentTrack
                    val now       = System.currentTimeMillis()

                    _uiState.update {
                        it.copy(
                            isPlaying      = if (now < isPlayingLockUntil) it.isPlaying else state.isPlaying,
                            currentTrack   = newTrack,
                            progressMs     = state.progressMs,
                            durationMs     = newTrack?.durationMs ?: 0L,
                            shuffleEnabled = if (now < shuffleLockUntil) it.shuffleEnabled else state.shuffleState,
                            repeatMode     = if (now < repeatLockUntil) it.repeatMode else when (state.repeatState) {
                                "track"   -> RepeatMode.TRACK
                                "context" -> RepeatMode.CONTEXT
                                else      -> RepeatMode.OFF
                            },
                            isLoading = false,
                            error     = null,
                        )
                    }

                    if (newTrack != null && newTrack.id != prevTrack?.id) {
                        checkIsLiked(newTrack.id)
                    }

                    // Sync tick job with actual playing state on every poll
                    val playing = _uiState.value.isPlaying
                    if (playing && progressTickJob?.isActive != true) {
                        startProgressTick()
                    } else if (!playing) {
                        progressTickJob?.cancel()
                    }
                }
            },
            onFailure = { e ->
                when {
                    e.message?.contains("429") == true ->
                        pollBackoffUntil = System.currentTimeMillis() + 60_000L
                    e.isTransientNetworkError() -> { /* silent — next poll will retry */ }
                    else -> _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            },
        )
    }

    // ── Progress tick ─────────────────────────────────────────────────────────

    private fun startProgressTick() {
        progressTickJob?.cancel()
        progressTickJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                _uiState.update { s ->
                    val next = (s.progressMs + 1_000L).coerceAtMost(s.durationMs)
                    s.copy(progressMs = next)
                }
            }
        }
    }

    private suspend fun checkIsLiked(trackId: String) {
        repository.isTrackSaved(trackId).fold(
            onSuccess = { liked -> _uiState.update { it.copy(isLiked = liked) } },
            onFailure = { e -> _uiState.update { it.copy(error = "Like check failed: ${e.message}") } },
        )
    }

    fun recheckLiked(trackId: String) {
        viewModelScope.launch { checkIsLiked(trackId) }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun playTrack(uri: String, contextUri: String? = null, uris: List<String>? = null, index: Int? = null) {
        isPlayingLockUntil = System.currentTimeMillis() + 5_000L
        _uiState.update { it.copy(isPlaying = true, isLiked = false, error = null) }
        startProgressTick()
        val trackId = uri.substringAfterLast(":")
        viewModelScope.launch { checkIsLiked(trackId) }
        viewModelScope.launch {
            repository.play(
                uri        = uri,
                contextUri = contextUri,
                offsetUri  = if (contextUri != null) uri else null,
                uris       = uris,
            ).fold(
                onSuccess = {
                    delay(1_000L)
                    fetchPlayerState()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        // No active device. Use SDK to start Spotify with the right track,
                        // then poll the Web API to restore the full queue once the device registers.
                        val sdkSuccess = when {
                            contextUri != null && index != null -> {
                                val ok = remoteManager.connectAndPlay(contextUri)
                                if (ok) remoteManager.skipToIndex(contextUri, index)
                                ok
                            }
                            else -> remoteManager.connectAndPlay(uri)
                        }
                        if (sdkSuccess) {
                            // Restore the full queue (uris list or playlist context) once Spotify
                            // registers as an active Connect device — the SDK bypasses Connect so
                            // it plays immediately but doesn't appear via the Web API yet.
                            val needsRestore = uris != null || (contextUri != null && index == null)
                            if (needsRestore) {
                                val sdkStartedAt = System.currentTimeMillis()
                                val deadline = sdkStartedAt + 10_000L
                                while (System.currentTimeMillis() < deadline) {
                                    delay(1_500L)
                                    // Pass elapsed time so the restore picks up where the SDK left
                                    // off instead of restarting the track from the beginning.
                                    val elapsedMs = System.currentTimeMillis() - sdkStartedAt
                                    val ok = when {
                                        uris != null       -> repository.play(uris = uris, positionMs = elapsedMs).isSuccess
                                        contextUri != null -> repository.play(contextUri = contextUri, offsetUri = uri, positionMs = elapsedMs).isSuccess
                                        else               -> true
                                    }
                                    if (ok) break
                                }
                            }
                            delay(500L)
                            fetchPlayerState()
                        } else {
                            _uiState.update { it.copy(error = "Couldn't connect to Spotify.", isPlaying = false) }
                            progressTickJob?.cancel()
                        }
                    } else {
                        _uiState.update { it.copy(error = e.message, isPlaying = false) }
                        progressTickJob?.cancel()
                    }
                },
            )
        }
    }

    fun playPause() {
        val state = _uiState.value
        isPlayingLockUntil = System.currentTimeMillis() + 5_000L
        viewModelScope.launch {
            if (state.isPlaying) {
                _uiState.update { it.copy(isPlaying = false) }
                progressTickJob?.cancel()
                remoteManager.pause()
                repository.pause()
            } else {
                _uiState.update { it.copy(isPlaying = true) }
                startProgressTick()
                remoteManager.resume()
                repository.play()
            }
        }
    }

    fun skipNext() {
        viewModelScope.launch {
            repository.skipNext().fold(
                onSuccess = {
                    delay(500L)
                    fetchPlayerState()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        remoteManager.skipNext()
                        delay(500L)
                        fetchPlayerState()
                    }
                },
            )
        }
    }

    fun skipPrevious() {
        viewModelScope.launch {
            repository.skipPrevious().fold(
                onSuccess = {
                    delay(500L)
                    fetchPlayerState()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        remoteManager.skipPrevious()
                        delay(500L)
                        fetchPlayerState()
                    }
                },
            )
        }
    }

    fun seekTo(fraction: Float) {
        val durationMs = _uiState.value.durationMs
        val posMs = (fraction * durationMs).toLong()
        _uiState.update { it.copy(progressMs = posMs) }
        viewModelScope.launch { repository.seek(posMs) }
    }

    fun toggleShuffle() {
        val newState = !_uiState.value.shuffleEnabled
        shuffleLockUntil = System.currentTimeMillis() + 5_000L
        _uiState.update { it.copy(shuffleEnabled = newState) }
        viewModelScope.launch {
            remoteManager.setShuffle(newState)
            repository.setShuffle(newState)
        }
    }

    fun cycleRepeat() {
        val next = when (_uiState.value.repeatMode) {
            RepeatMode.OFF     -> RepeatMode.CONTEXT
            RepeatMode.CONTEXT -> RepeatMode.TRACK
            RepeatMode.TRACK   -> RepeatMode.OFF
        }
        repeatLockUntil = System.currentTimeMillis() + 5_000L
        _uiState.update { it.copy(repeatMode = next) }
        val apiStr = when (next) {
            RepeatMode.OFF     -> "off"
            RepeatMode.CONTEXT -> "context"
            RepeatMode.TRACK   -> "track"
        }
        viewModelScope.launch {
            remoteManager.setRepeat(next.ordinal)
            repository.setRepeat(apiStr)
        }
    }

    fun toggleLike() {
        val state   = _uiState.value
        val trackId = state.currentTrack?.id ?: return
        val newLiked = !state.isLiked
        _uiState.update { it.copy(isLiked = newLiked) }
        viewModelScope.launch {
            if (newLiked) repository.saveTrack(trackId)
            else          repository.removeTrack(trackId)
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerMinutes = minutes, sleepTimerTotalMinutes = minutes) }
        if (minutes <= 0) return

        sleepTimerJob = viewModelScope.launch {
            repeat(minutes) { elapsed ->
                delay(60_000L)
                val remaining = minutes - elapsed - 1
                _uiState.update { it.copy(sleepTimerMinutes = remaining) }
                if (remaining == 0) {
                    remoteManager.pause()
                    repository.pause()
                    _uiState.update { it.copy(isPlaying = false, sleepTimerTotalMinutes = 0) }
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        sleepTimerJob?.cancel()
        progressTickJob?.cancel()
        remoteManager.disconnect()
        super.onCleared()
    }
}

private fun Throwable.isTransientNetworkError(): Boolean =
    cause is java.net.UnknownHostException ||
    cause is java.net.SocketException ||
    message?.contains("Unable to resolve host") == true ||
    message?.contains("Failed to connect") == true

class PlayerViewModelFactory(private val container: com.crsmthw.lyra.di.AppContainer) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlayerViewModel(container.spotifyRepository, container.remoteManager) as T
}
