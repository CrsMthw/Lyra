package com.crsmthw.lyra.ui.screens.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumDetailUiState(
    val album    : SpotifyAlbumFull? = null,
    val isLoading: Boolean           = true,
    val error    : String?           = null,
)

class AlbumDetailViewModel(
    private val repository: SpotifyRepository,
    libraryCache          : LibraryCache,
    private val albumId   : String,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _state

    /** Backs the song touch-and-hold menu for album track rows. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)

    init { loadAlbum() }

    private fun loadAlbum() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getAlbum(albumId).fold(
                onSuccess = { album -> _state.update { it.copy(album = album, isLoading = false) } },
                onFailure = { e   -> _state.update { it.copy(error = e.message, isLoading = false) } },
            )
        }
    }
}

class AlbumDetailViewModelFactory(
    private val container: AppContainer,
    private val albumId  : String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AlbumDetailViewModel(container.spotifyRepository, container.libraryCache, albumId) as T
}
