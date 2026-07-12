package com.crsmthw.lyra.ui.screens.artist

import android.util.Log
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

    init {
        load()
        viewModelScope.launch {
            // Seed from the cached followed-artists list (kept in sync by the toggles + the
            // Artists filter's full fetch) so the heart is usable even when the network check
            // misbehaves. A cached "followed" wins over an API false: me/library/contains has
            // been seen returning nothing for artist uris.
            val cachedFollowed = withContext(Dispatchers.IO) {
                libraryCache.load()?.followedArtists?.any { it.id == artistId }
            }
            if (cachedFollowed == true) {
                _state.update { it.copy(isFollowed = true) }
                return@launch
            }
            repository.isArtistFollowed(artistId).fold(
                onSuccess = { followed -> _state.update { it.copy(isFollowed = followed) } },
                onFailure = { e ->
                    // Never leave the heart stuck disabled (isFollowed == null): assume not
                    // followed — the first toggle then PUTs and is correct either way.
                    Log.w("ArtistDetailVM", "me/following/contains failed for $artistId", e)
                    _state.update { it.copy(isFollowed = false) }
                },
            )
        }
    }

    /** Optimistic follow/unfollow via me/following (artists aren't accepted by me/library); keeps the Artists filter in sync. */
    fun toggleFollowed() {
        val followed = _state.value.isFollowed ?: return
        _state.update { it.copy(isFollowed = !followed) }
        viewModelScope.launch {
            val result = if (followed) repository.unfollowArtist(artistId)
                         else repository.followArtist(artistId)
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
                onFailure = { e ->
                    Log.w("ArtistDetailVM", "follow toggle failed for $artistId", e)
                    _state.update { it.copy(isFollowed = followed) }   // revert
                },
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
