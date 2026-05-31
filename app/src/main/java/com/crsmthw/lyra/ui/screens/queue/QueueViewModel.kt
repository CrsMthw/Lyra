package com.crsmthw.lyra.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QueueUiState(
    val isLoading        : Boolean           = true,
    val currentlyPlaying : SpotifyTrack?     = null,
    val queue            : List<SpotifyTrack> = emptyList(),
    val error            : String?           = null,
)

class QueueViewModel(
    private val repository        : SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState

    init {
        // Initial fetch on screen open
        viewModelScope.launch { fetchQueue() }

        // Re-fetch immediately when the playing track changes
        viewModelScope.launch {
            playerStateManager.state
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .drop(1) // initial fetch already launched above
                .collect { fetchQueue() }
        }
    }

    fun refresh() {
        viewModelScope.launch { fetchQueue() }
    }

    private suspend fun fetchQueue() {
        repository.getQueue().fold(
            onSuccess = { response ->
                _uiState.update {
                    if (response == null) {
                        it.copy(isLoading = false, currentlyPlaying = null, queue = emptyList(), error = null)
                    } else {
                        it.copy(
                            isLoading        = false,
                            currentlyPlaying = response.currentlyPlaying
                                ?.takeIf { t -> t.uri.startsWith("spotify:track:") },
                            queue            = response.queue
                                .filter { t -> t.uri.startsWith("spotify:track:") && t.isPlayable != false }
                                .distinctBy { it.uri },
                            error            = null,
                        )
                    }
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            },
        )
    }
}

class QueueViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        QueueViewModel(container.spotifyRepository, container.playerStateManager) as T
}
