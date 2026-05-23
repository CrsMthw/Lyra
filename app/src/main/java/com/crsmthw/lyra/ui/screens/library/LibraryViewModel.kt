package com.crsmthw.lyra.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.CachedTrackList
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LibraryCacheData
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.*
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.util.MosaicGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val playlists             : List<SpotifyPlaylist>  = emptyList(),
    val featuredPlaylists     : List<SpotifyPlaylist>  = emptyList(),
    val likedSongCount        : Int                    = 0,
    val currentPlaylist       : SpotifyPlaylist?        = null,
    val currentTracks         : List<SpotifyTrack>     = emptyList(),
    val isLoading             : Boolean                = true,
    val isLoadingTracks       : Boolean                = false,
    val isLoadingMoreTracks   : Boolean                = false,
    val likedSongsOffset      : Int                    = 0,
    val likedSongsTotal       : Int                    = 0,
    val error                 : String?                = null,  // blocking — shown when no cache
    val refreshError          : String?                = null,  // non-blocking — shown as icon when cache is visible
    val user                  : SpotifyUser?           = null,
    val playlistsWithMosaics  : Set<String>            = emptySet(),
)

class LibraryViewModel(
    private val repository      : SpotifyRepository,
    private val cache           : LibraryCache,
    private val mosaicGenerator : MosaicGenerator,
    private val remoteManager   : SpotifyRemoteManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    init {
        _uiState.update { it.copy(playlistsWithMosaics = mosaicGenerator.existingIds()) }
        loadLibrary()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, refreshError = null) }

            // Show cached data immediately so the screen is never blank
            val cached = withContext(Dispatchers.IO) { cache.load() }
            val hasCache = cached != null && cached.playlists.isNotEmpty()
            if (cached != null && hasCache) {
                _uiState.update { it.copy(
                    playlists         = cached.playlists,
                    featuredPlaylists = cached.featuredPlaylists,
                    likedSongCount    = cached.likedSongCount,
                    user              = cached.user,
                    isLoading         = false,
                )}
                // Generate mosaics for playlists with cached track lists but no mosaic yet
                generateMissingMosaicsAsync(cached.playlists + cached.featuredPlaylists, cached.trackLists)
            } else {
                _uiState.update { it.copy(isLoading = true) }
            }

            // Refresh from network (silently if we have cached data)
            repository.getCurrentUser().fold(
                onSuccess = { user -> _uiState.update { it.copy(user = user) } },
                onFailure = { },
            )

            val playlistsResult = repository.getUserPlaylists()
            if (playlistsResult.isFailure) {
                val e = playlistsResult.exceptionOrNull()
                if (!e.isTransientNetworkError()) {
                    val msg = e?.message
                    if (hasCache) {
                        _uiState.update { it.copy(refreshError = msg) }
                    } else {
                        _uiState.update { it.copy(error = msg, isLoading = false) }
                    }
                }
                return@launch
            }
            _uiState.update { it.copy(playlists = playlistsResult.getOrThrow().items) }

            repository.getLikedSongs(limit = 1).fold(
                onSuccess = { resp -> _uiState.update { it.copy(likedSongCount = resp.total) } },
                onFailure = { },
            )

            repository.getFeaturedPlaylists().fold(
                onSuccess = { resp -> _uiState.update { it.copy(featuredPlaylists = resp.playlists.items) } },
                onFailure = { },
            )

            _uiState.update { it.copy(isLoading = false) }

            // Persist refreshed data, preserving existing track list cache
            val s = _uiState.value
            withContext(Dispatchers.IO) {
                val existingTrackLists = cache.load()?.trackLists ?: emptyMap()
                cache.save(LibraryCacheData(
                    playlists         = s.playlists,
                    featuredPlaylists = s.featuredPlaylists,
                    likedSongCount    = s.likedSongCount,
                    user              = s.user,
                    trackLists        = existingTrackLists,
                ))
            }
            // Generate mosaics for any new playlists that now have cached track lists
            generateMissingMosaicsAsync(s.playlists + s.featuredPlaylists, cache.load()?.trackLists ?: emptyMap())
        }
    }

    private fun generateMissingMosaicsAsync(
        playlists  : List<SpotifyPlaylist>,
        trackLists : Map<String, CachedTrackList>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            for (playlist in playlists) {
                if (playlist.id in _uiState.value.playlistsWithMosaics) continue
                val trackList = trackLists[playlist.id] ?: continue
                if (playlist.snapshotId != null && trackList.snapshotId != playlist.snapshotId) continue
                if (mosaicGenerator.generate(playlist.id, trackList.tracks)) {
                    _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                }
            }
        }
    }

    fun selectPlaylist(playlist: SpotifyPlaylist) {
        if (_uiState.value.currentPlaylist?.id == playlist.id) return
        _uiState.update { it.copy(currentPlaylist = playlist, isLoadingTracks = true, currentTracks = emptyList(), error = null) }
        viewModelScope.launch {
            val snapshotId = playlist.snapshotId
            if (snapshotId != null) {
                val cached = withContext(Dispatchers.IO) { cache.loadTrackList(playlist.id) }
                if (cached != null && cached.snapshotId == snapshotId) {
                    _uiState.update { it.copy(currentTracks = cached.tracks, isLoadingTracks = false) }
                    // Generate mosaic if missing (tracks already cached, Coil has album art)
                    if (playlist.id !in _uiState.value.playlistsWithMosaics) {
                        withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, cached.tracks) }
                        _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                    }
                    return@launch
                }
            }

            repository.getPlaylistTracks(playlist.id).fold(
                onSuccess = { resp ->
                    val tracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                    _uiState.update { it.copy(currentTracks = tracks, isLoadingTracks = false) }
                    if (snapshotId != null) {
                        withContext(Dispatchers.IO) { cache.saveTrackList(playlist.id, snapshotId, tracks) }
                    }
                    // Always regenerate mosaic on fresh fetch (snapshot changed or first load)
                    withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, tracks) }
                    _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                },
                onFailure = { e ->
                    val msg = if (e.message?.contains("403") == true)
                        "Track list unavailable for this playlist. You can still play it with the ▶ button."
                    else e.message
                    _uiState.update { it.copy(error = msg, isLoadingTracks = false) }
                },
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(
            currentPlaylist     = null,
            currentTracks       = emptyList(),
            isLoadingTracks     = false,
            isLoadingMoreTracks = false,
            likedSongsOffset    = 0,
            likedSongsTotal     = 0,
            error               = null,
        )}
    }

    fun playPlaylist(uri: String) {
        viewModelScope.launch {
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                }
            }
        }
    }

    fun shufflePlaylist(uri: String) {
        viewModelScope.launch {
            repository.setShuffle(true)
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                }
            }
        }
    }

    fun selectLikedSongs() {
        _uiState.update { it.copy(
            currentPlaylist     = null,
            currentTracks       = emptyList(),
            error               = null,
            likedSongsOffset    = 0,
            likedSongsTotal     = 0,
            isLoadingMoreTracks = false,
        )}
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.loadTrackList(LIKED_SONGS_KEY) }

            if (cached != null) {
                // Show cached tracks immediately — no loading spinner for the user
                val cachedCount = cached.snapshotId.toIntOrNull() ?: cached.tracks.size
                _uiState.update { it.copy(
                    currentTracks    = cached.tracks,
                    isLoadingTracks  = false,
                    likedSongsOffset = cached.tracks.size,
                    likedSongsTotal  = cachedCount,
                )}

                // Background count check (1 lightweight call) — detects adds/removes
                // without requiring the user to close and reopen the app.
                repository.getLikedSongs(limit = 1).fold(
                    onSuccess = { resp ->
                        _uiState.update { it.copy(likedSongCount = resp.total) }
                        if (resp.total != cachedCount) {
                            fetchAndReplaceLikedSongs()
                        }
                    },
                    onFailure = { },  // network unavailable — keep showing cache
                )
                return@launch
            }

            // No cache — fetch with loading indicator
            _uiState.update { it.copy(isLoadingTracks = true) }
            fetchAndReplaceLikedSongs()
        }
    }

    private suspend fun fetchAndReplaceLikedSongs() {
        repository.getLikedSongs(limit = 50, offset = 0).fold(
            onSuccess = { resp ->
                val tracks = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                _uiState.update { it.copy(
                    currentTracks    = tracks,
                    isLoadingTracks  = false,
                    likedSongsOffset = tracks.size,
                    likedSongsTotal  = resp.total,
                    likedSongCount   = resp.total,
                )}
                withContext(Dispatchers.IO) {
                    cache.saveTrackList(LIKED_SONGS_KEY, resp.total.toString(), tracks)
                }
            },
            onFailure = { e ->
                _uiState.update { it.copy(error = e.message, isLoadingTracks = false) }
            },
        )
    }

    fun loadMoreLikedSongs() {
        val s = _uiState.value
        if (s.isLoadingMoreTracks || s.likedSongsOffset >= s.likedSongsTotal) return
        _uiState.update { it.copy(isLoadingMoreTracks = true) }
        viewModelScope.launch {
            repository.getLikedSongs(limit = 50, offset = s.likedSongsOffset).fold(
                onSuccess = { resp ->
                    val newTracks = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                    val allTracks = s.currentTracks + newTracks
                    _uiState.update { it.copy(
                        currentTracks       = allTracks,
                        isLoadingMoreTracks = false,
                        likedSongsOffset    = allTracks.size,
                        likedSongsTotal     = resp.total,
                    )}
                    withContext(Dispatchers.IO) {
                        cache.saveTrackList(LIKED_SONGS_KEY, resp.total.toString(), allTracks)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMoreTracks = false) }
                },
            )
        }
    }

    companion object {
        private const val LIKED_SONGS_KEY = "liked_songs"
    }
}

private fun Throwable?.isTransientNetworkError(): Boolean =
    this?.cause is java.net.UnknownHostException ||
    this?.cause is java.net.SocketException ||
    this?.message?.contains("Unable to resolve host") == true ||
    this?.message?.contains("Failed to connect") == true

class LibraryViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LibraryViewModel(container.spotifyRepository, container.libraryCache, container.mosaicGenerator, container.remoteManager) as T
}
