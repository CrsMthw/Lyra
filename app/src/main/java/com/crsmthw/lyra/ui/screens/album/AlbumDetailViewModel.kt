package com.crsmthw.lyra.ui.screens.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumDetailUiState(
    val album    : SpotifyAlbumFull? = null,
    val isLoading: Boolean           = true,
    val error    : String?           = null,
    val isSaved  : Boolean?          = null,   // null = still resolving (save button disabled)
)

class AlbumDetailViewModel(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
    private val albumId     : String,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _state

    /** Backs the song touch-and-hold menu for album track rows. */
    val trackActions = TrackActionsController(repository, libraryCache, viewModelScope)

    private val albumUri = "spotify:album:$albumId"

    init {
        loadAlbum()
        viewModelScope.launch {
            repository.isInLibrary(albumUri).onSuccess { saved ->
                _state.update { it.copy(isSaved = saved) }
            }
        }
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getAlbum(albumId).fold(
                onSuccess = { album -> _state.update { it.copy(album = album, isLoading = false) } },
                onFailure = { e   -> _state.update { it.copy(error = e.message, isLoading = false) } },
            )
        }
    }

    /** Optimistic save/unsave via the unified me/library; keeps the Library Albums filter in sync. */
    fun toggleSaved() {
        val saved = _state.value.isSaved ?: return
        _state.update { it.copy(isSaved = !saved) }
        viewModelScope.launch {
            val result = if (saved) repository.removeFromLibrary(albumUri)
                         else repository.saveToLibrary(albumUri)
            result.fold(
                onSuccess = {
                    withContext(Dispatchers.IO) {
                        if (saved) {
                            libraryCache.removeSavedAlbum(albumId)
                        } else {
                            _state.value.album?.let { full ->
                                libraryCache.addSavedAlbum(SpotifyAlbum(
                                    id          = full.id,
                                    name        = full.name,
                                    images      = full.images,
                                    artists     = full.artists,
                                    releaseDate = full.releaseDate,
                                    albumType   = full.albumType,
                                ))
                            }
                        }
                    }
                },
                onFailure = { _state.update { it.copy(isSaved = saved) } },   // revert
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
