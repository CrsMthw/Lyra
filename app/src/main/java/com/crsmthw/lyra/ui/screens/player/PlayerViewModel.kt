package com.crsmthw.lyra.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

data class PlayerUiState(
    val isPlaying             : Boolean       = false,
    val currentTrack          : SpotifyTrack? = null,
    val progressMs            : Long          = 0L,
    val durationMs            : Long          = 0L,
    val shuffleEnabled        : Boolean       = false,
    val repeatMode            : RepeatMode    = RepeatMode.OFF,
    val isLiked               : Boolean       = false,
    val sleepTimerMinutes     : Int           = 0,
    val sleepTimerTotalMinutes: Int           = 0,
    val error                 : String?       = null,
) {
    val progress: Float
        get() = if (durationMs > 0L) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(
    private val playerStateManager: PlayerStateManager,
    private val repository        : SpotifyRepository,
    private val remoteManager     : SpotifyRemoteManager,
    private val libraryCache      : LibraryCache,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val _pickerState = MutableStateFlow(PlaylistPickerState())
    val pickerState: StateFlow<PlaylistPickerState> = _pickerState

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
                        error                  = null,
                    )
                }
                val newTrack = state.currentTrack
                if (newTrack != null && newTrack.id != prevTrack?.id) {
                    checkIsLiked(newTrack.id)
                }
            }
        }
        remoteManager.connect(onConnected = { }, onFailure = { })
    }

    private suspend fun checkIsLiked(trackId: String) {
        repository.isTrackSaved(trackId).fold(
            onSuccess = { liked -> _uiState.update { it.copy(isLiked = liked) } },
            onFailure = { },
        )
    }

    fun recheckLiked(trackId: String) {
        viewModelScope.launch { checkIsLiked(trackId) }
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
        val state   = _uiState.value
        val trackId = state.currentTrack?.id ?: return
        val newLiked = !state.isLiked
        _uiState.update { it.copy(isLiked = newLiked) }
        viewModelScope.launch {
            if (newLiked) repository.saveTrack(trackId)
            else          repository.removeTrack(trackId)
        }
    }

    // ── Playlist picker ───────────────────────────────────────────────────────

    fun loadOwnedPlaylists() {
        val currentTrackId = _uiState.value.currentTrack?.id ?: return
        _pickerState.update { it.copy(isLoading = true, playlists = emptyList(), containingPlaylistIds = emptySet(), addResult = null) }
        viewModelScope.launch {
            val userId = libraryCache.load()?.user?.id
                ?: repository.getCurrentUser().getOrNull()?.id
                ?: run { _pickerState.update { it.copy(isLoading = false) }; return@launch }

            val playlists = libraryCache.load()?.playlists
                ?.takeIf { it.isNotEmpty() }
                ?: repository.getUserPlaylists().getOrNull()?.items
                ?: emptyList()

            val owned = playlists.filter { it.owner.id == userId }

            val cacheData = libraryCache.load()
            val containing = owned.filter { playlist ->
                cacheData?.trackLists?.get(playlist.id)?.tracks?.any { it.id == currentTrackId } == true
            }.map { it.id }.toSet()

            _pickerState.update { it.copy(isLoading = false, playlists = owned, containingPlaylistIds = containing) }
        }
    }

    fun togglePlaylistTrack(playlist: SpotifyPlaylist) {
        val trackUri = _uiState.value.currentTrack?.uri ?: return
        val isContained = playlist.id in _pickerState.value.containingPlaylistIds
        viewModelScope.launch {
            if (isContained) {
                repository.removeTrackFromPlaylist(playlist.id, trackUri).fold(
                    onSuccess = {
                        _pickerState.update { s ->
                            s.copy(
                                containingPlaylistIds = s.containingPlaylistIds - playlist.id,
                                addResult             = AddToPlaylistResult.Removed(playlist.name),
                            )
                        }
                    },
                    onFailure = { e ->
                        _pickerState.update { it.copy(addResult = errorResult(e)) }
                    },
                )
            } else {
                repository.addTrackToPlaylist(playlist.id, trackUri).fold(
                    onSuccess = {
                        _pickerState.update { s ->
                            s.copy(
                                containingPlaylistIds = s.containingPlaylistIds + playlist.id,
                                addResult             = AddToPlaylistResult.Added(playlist.name),
                            )
                        }
                    },
                    onFailure = { e ->
                        _pickerState.update { it.copy(addResult = errorResult(e)) }
                    },
                )
            }
        }
    }

    private fun errorResult(e: Throwable): AddToPlaylistResult =
        if (e.message?.contains("403") == true) AddToPlaylistResult.NeedsReconnect
        else AddToPlaylistResult.Error(e.message)

    fun clearPickerResult() {
        _pickerState.update { it.copy(addResult = null) }
    }

    // ── Play track (keeps SDK fallback logic, always user-initiated) ──────────

    fun playTrack(uri: String, contextUri: String? = null, uris: List<String>? = null, index: Int? = null) {
        playerStateManager.lockIsPlaying()
        _uiState.update { it.copy(isPlaying = true, isLiked = false, error = null) }
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
                    playerStateManager.fetchOnce()
                },
                onFailure = { e ->
                    if (e.message?.contains("404") == true) {
                        val sdkSuccess = when {
                            contextUri != null && index != null -> {
                                val ok = remoteManager.connectAndPlay(contextUri)
                                if (ok) remoteManager.skipToIndex(contextUri, index)
                                ok
                            }
                            else -> remoteManager.connectAndPlay(uri)
                        }
                        if (sdkSuccess) {
                            val needsRestore = uris != null || (contextUri != null && index == null)
                            if (needsRestore) {
                                val sdkStartedAt = System.currentTimeMillis()
                                val deadline = sdkStartedAt + 10_000L
                                while (System.currentTimeMillis() < deadline) {
                                    delay(1_500L)
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
                            playerStateManager.fetchOnce()
                        } else {
                            _uiState.update { it.copy(error = "Couldn't connect to Spotify.", isPlaying = false) }
                        }
                    } else {
                        _uiState.update { it.copy(error = e.message, isPlaying = false) }
                    }
                },
            )
        }
    }
}

class PlayerViewModelFactory(private val container: com.crsmthw.lyra.di.AppContainer) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlayerViewModel(container.playerStateManager, container.spotifyRepository, container.remoteManager, container.libraryCache) as T
}
