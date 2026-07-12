package com.crsmthw.lyra.ui.screens.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyArtist
import com.crsmthw.lyra.data.remote.model.SpotifyArtistFull
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArtistDetailUiState(
    val artist        : SpotifyArtistFull? = null,
    val albums        : List<SpotifyAlbum> = emptyList(),
    val albumsNext    : String?            = null,
    val isLoading     : Boolean            = true,
    val isLoadingMore : Boolean            = false,
    val error         : String?            = null,
    val isFollowed    : Boolean?           = null,   // null = still resolving (follow button disabled)
)

class ArtistDetailViewModel(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
    private val artistId    : String,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _state

    private val artistUri = "spotify:artist:$artistId"

    init {
        load()
        viewModelScope.launch {
            repository.isInLibrary(artistUri).onSuccess { followed ->
                _state.update { it.copy(isFollowed = followed) }
            }
        }
    }

    /** Optimistic follow/unfollow via the unified me/library; keeps the Artists filter in sync. */
    fun toggleFollowed() {
        val followed = _state.value.isFollowed ?: return
        _state.update { it.copy(isFollowed = !followed) }
        viewModelScope.launch {
            val result = if (followed) repository.removeFromLibrary(artistUri)
                         else repository.saveToLibrary(artistUri)
            result.fold(
                onSuccess = {
                    withContext(Dispatchers.IO) {
                        if (followed) {
                            libraryCache.removeFollowedArtist(artistId)
                        } else {
                            _state.value.artist?.let { full ->
                                libraryCache.addFollowedArtist(SpotifyArtist(
                                    id     = full.id,
                                    name   = full.name,
                                    images = full.images,
                                ))
                            }
                        }
                    }
                },
                onFailure = { _state.update { it.copy(isFollowed = followed) } },   // revert
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            val artistDeferred = async { repository.getArtist(artistId) }
            val albumsDeferred = async { repository.getArtistAlbums(artistId) }

            val artistResult = artistDeferred.await()
            val albumsPage   = albumsDeferred.await().getOrNull()

            artistResult.fold(
                onSuccess = { artist ->
                    _state.update { it.copy(
                        artist     = artist,
                        albums     = albumsPage?.items ?: emptyList(),
                        albumsNext = albumsPage?.next,
                        isLoading  = false,
                    )}
                },
                onFailure = { e ->
                    _state.update { it.copy(error = e.message, isLoading = false) }
                },
            )
        }
    }

    fun loadMoreAlbums() {
        _state.value.albumsNext ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            repository.getArtistAlbums(artistId, offset = _state.value.albums.size).fold(
                onSuccess = { page ->
                    _state.update { s -> s.copy(
                        albums        = s.albums + page.items,
                        albumsNext    = page.next,
                        isLoadingMore = false,
                    )}
                },
                onFailure = { _state.update { it.copy(isLoadingMore = false) } },
            )
        }
    }
}

class ArtistDetailViewModelFactory(
    private val container: AppContainer,
    private val artistId : String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ArtistDetailViewModel(container.spotifyRepository, container.libraryCache, artistId) as T
}
