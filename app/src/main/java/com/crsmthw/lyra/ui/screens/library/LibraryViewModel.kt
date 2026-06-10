package com.crsmthw.lyra.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.CachedTrackList
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.local.LibraryCacheData
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.remote.SpotifyRemoteManager
import com.crsmthw.lyra.data.remote.model.*
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.components.TrackActionsController
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
    val isRefreshing          : Boolean                = false,
    val isLibraryRefreshing   : Boolean                = false,
    val likedSongsOffset      : Int                    = 0,
    val likedSongsTotal       : Int                    = 0,
    val playlistTracksOffset  : Int                    = 0,
    val playlistTracksTotal   : Int                    = 0,
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
    private val playerStateManager: PlayerStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    /** Backs the song touch-and-hold menu for every track list this screen shows. */
    val trackActions = TrackActionsController(repository, cache, viewModelScope)

    /**
     * Removes the long-pressed track from the playlist it's currently shown in (only reachable
     * when that playlist is owned). Drops the row immediately and keeps the cached track list in
     * sync so re-opening the playlist doesn't resurrect it.
     */
    fun removeTrackFromCurrentPlaylist() {
        val target   = trackActions.state.value.target ?: return
        val playlist = target.removable ?: return
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlist.id, target.uri).onSuccess {
                _uiState.update { s ->
                    s.copy(currentTracks = s.currentTracks.filterNot { it.uri == target.uri })
                }
                cache.loadTrackList(playlist.id)?.let { cached ->
                    cache.saveTrackList(
                        playlist.id,
                        cached.snapshotId,
                        cached.tracks.filterNot { it.uri == target.uri },
                    )
                }
                trackActions.dismiss()
            }
        }
    }

    /**
     * Deletes an owned playlist (Spotify unfollow). On success removes it from the in-memory list
     * and cache, and closes the detail view if it was the one open. Caller guards that the playlist
     * is owned (never Liked Songs / followed).
     */
    fun deletePlaylist(playlist: SpotifyPlaylist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist.id).fold(
                onSuccess = {
                    cache.removePlaylist(playlist.id)
                    _uiState.update { s ->
                        val wasOpen = s.currentPlaylist?.id == playlist.id
                        s.copy(
                            playlists       = s.playlists.filterNot { it.id == playlist.id },
                            currentPlaylist = if (wasOpen) null else s.currentPlaylist,
                            currentTracks   = if (wasOpen) emptyList() else s.currentTracks,
                            refreshError    = null,
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(refreshError = e.message) } },
            )
        }
    }

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

    fun refreshLibrary() {
        if (_uiState.value.isLibraryRefreshing) return
        _uiState.update { it.copy(isLibraryRefreshing = true, refreshError = null) }
        viewModelScope.launch {
            repository.getCurrentUser().fold(
                onSuccess = { user -> _uiState.update { it.copy(user = user) } },
                onFailure = { },
            )

            val playlistsResult = repository.getUserPlaylists()
            if (playlistsResult.isFailure) {
                val e = playlistsResult.exceptionOrNull()
                if (!e.isTransientNetworkError()) {
                    _uiState.update { it.copy(refreshError = e?.message) }
                }
                _uiState.update { it.copy(isLibraryRefreshing = false) }
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

            _uiState.update { it.copy(isLibraryRefreshing = false) }

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
                if (playlist.thumbnailUrl.isNotBlank()) continue
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
        _uiState.update { it.copy(
            currentPlaylist      = playlist,
            isLoadingTracks      = true,
            currentTracks        = emptyList(),
            playlistTracksOffset = 0,
            playlistTracksTotal  = playlist.trackCount,   // metadata total; refined from the response
            error                = null,
        ) }
        viewModelScope.launch {
            val snapshotId = playlist.snapshotId
            if (snapshotId != null) {
                val cached = withContext(Dispatchers.IO) { cache.loadTrackList(playlist.id) }
                if (cached != null && cached.snapshotId == snapshotId) {
                    // Snapshot matches → cache is current. Show it and seed pagination from it; whatever
                    // pages were loaded+cached before are preserved (no page-0 refetch that would clobber).
                    _uiState.update { it.copy(
                        currentTracks        = cached.tracks,
                        isLoadingTracks      = false,
                        playlistTracksOffset = cached.tracks.size,
                        playlistTracksTotal  = maxOf(playlist.trackCount, cached.tracks.size),
                    ) }
                    if (playlist.id !in _uiState.value.playlistsWithMosaics && playlist.thumbnailUrl.isBlank()) {
                        withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, cached.tracks) }
                        _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                    }
                    // The cached playlist's track-count metadata can be stale or truncated (older app
                    // versions only ever cached the first 50), which would wrongly disable paging.
                    // Confirm the authoritative total cheaply (limit=1 still returns the full `total`)
                    // so lazy-loading works on first open without needing a manual refresh.
                    repository.getPlaylistTracks(playlist.id, limit = 1, offset = 0).onSuccess { resp ->
                        if (_uiState.value.currentPlaylist?.id == playlist.id) {
                            _uiState.update { it.copy(playlistTracksTotal = resp.total) }
                        }
                    }
                    return@launch
                }
            }

            // No cache or stale snapshot — fetch the first page with the loading indicator
            repository.getPlaylistTracks(playlist.id).fold(
                onSuccess = { resp ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@fold
                    val tracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                    _uiState.update { it.copy(
                        currentTracks        = tracks,
                        isLoadingTracks      = false,
                        // Offset advances by the RAW page size (incl. filtered-out items) so the next
                        // page picks up where the API left off — avoids re-fetch/duplicates when a
                        // playlist contains unplayable tracks.
                        playlistTracksOffset = resp.items?.size ?: 0,
                        playlistTracksTotal  = resp.total,
                    ) }
                    if (snapshotId != null) {
                        withContext(Dispatchers.IO) { cache.saveTrackList(playlist.id, snapshotId, tracks) }
                    }
                    if (playlist.thumbnailUrl.isBlank()) {
                        withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, tracks) }
                        _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                    }
                },
                onFailure = { e ->
                    // Only reached when there was no usable cache (cache hit returns early above).
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
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                } else {
                    playerStateManager.releasePlayingOptimism()
                }
            }
        }
    }

    fun shufflePlaylist(uri: String) {
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.setShuffle(true)
            repository.play(contextUri = uri).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uri)
                } else {
                    playerStateManager.releasePlayingOptimism()
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
            val cached = withContext(Dispatchers.IO) { cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY) }

            if (cached != null) {
                // Show cached tracks immediately — no loading spinner for the user
                val cachedCount = cached.snapshotId.toIntOrNull() ?: cached.tracks.size
                _uiState.update { it.copy(
                    currentTracks    = cached.tracks,
                    isLoadingTracks  = false,
                    likedSongsOffset = cached.tracks.size,
                    likedSongsTotal  = cachedCount,
                )}

                // Background count check — detects external likes/unlikes since last open.
                repository.getLikedSongs(limit = 1).fold(
                    onSuccess = { resp ->
                        val newTotal = resp.total
                        _uiState.update { it.copy(likedSongCount = newTotal) }
                        val diff = newTotal - cachedCount
                        when {
                            diff in 1..50 -> {
                                // Songs added externally: fetch only the new ones and prepend.
                                repository.getLikedSongs(limit = diff, offset = 0).fold(
                                    onSuccess = { newResp ->
                                        val newTracks = (newResp.items ?: emptyList())
                                            .mapNotNull { it.track }
                                            .filter { it.isPlayable != false }
                                        val merged = newTracks + cached.tracks
                                        _uiState.update { s -> s.copy(
                                            currentTracks    = merged,
                                            likedSongsOffset = merged.size,
                                            likedSongsTotal  = newTotal,
                                        )}
                                        withContext(Dispatchers.IO) {
                                            cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, newTotal.toString(), merged)
                                        }
                                    },
                                    onFailure = { fetchAndReplaceLikedSongs() },
                                )
                            }
                            diff != 0 -> fetchAndReplaceLikedSongs()  // decreased or jumped >50
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
                    cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), tracks)
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
                        cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), allTracks)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMoreTracks = false) }
                },
            )
        }
    }

    /** Lazy-loads the next page of the open playlist's tracks (mirrors [loadMoreLikedSongs]). */
    fun loadMorePlaylistTracks() {
        val s = _uiState.value
        val playlist = s.currentPlaylist ?: return
        if (s.isLoadingMoreTracks || s.playlistTracksOffset >= s.playlistTracksTotal) return
        _uiState.update { it.copy(isLoadingMoreTracks = true) }
        viewModelScope.launch {
            repository.getPlaylistTracks(playlist.id, limit = 50, offset = s.playlistTracksOffset).fold(
                onSuccess = { resp ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) {
                        _uiState.update { it.copy(isLoadingMoreTracks = false) }
                        return@fold
                    }
                    val newTracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                    val allTracks = _uiState.value.currentTracks + newTracks
                    _uiState.update { it.copy(
                        currentTracks        = allTracks,
                        isLoadingMoreTracks  = false,
                        playlistTracksOffset = s.playlistTracksOffset + (resp.items?.size ?: 0),
                        playlistTracksTotal  = resp.total,
                    ) }
                    playlist.snapshotId?.let { snap ->
                        withContext(Dispatchers.IO) { cache.saveTrackList(playlist.id, snap, allTracks) }
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingMoreTracks = false) }
                },
            )
        }
    }

    fun refreshCurrentTracks() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val s = _uiState.value
            if (s.currentPlaylist == null) {
                repository.getLikedSongs(limit = 50, offset = 0).fold(
                    onSuccess = { resp ->
                        val freshFirst50 = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                        _uiState.update { it.copy(
                            currentTracks    = freshFirst50,
                            likedSongsOffset = freshFirst50.size,
                            likedSongsTotal  = resp.total,
                            likedSongCount   = resp.total,
                        )}
                        withContext(Dispatchers.IO) {
                            // Preserve tracks cached beyond page 0 so background-fetch progress isn't lost.
                            val beyond50 = cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.drop(50) ?: emptyList()
                            cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), freshFirst50 + beyond50)
                        }
                    },
                    onFailure = { },
                )
            } else {
                val playlist = s.currentPlaylist
                repository.getPlaylistTracks(playlist.id).fold(
                    onSuccess = { resp ->
                        val tracks = (resp.items ?: emptyList()).mapNotNull { it.resolvedTrack }.filter { it.isPlayable != false }
                        _uiState.update { it.copy(
                            currentTracks        = tracks,
                            playlistTracksOffset = resp.items?.size ?: 0,   // reset paging to page 0
                            playlistTracksTotal  = resp.total,
                        ) }
                        if (playlist.snapshotId != null) {
                            withContext(Dispatchers.IO) { cache.saveTrackList(playlist.id, playlist.snapshotId, tracks) }
                        }
                        if (playlist.thumbnailUrl.isBlank()) {
                            withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, tracks) }
                            _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                        }
                    },
                    onFailure = { },
                )
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
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
        LibraryViewModel(container.spotifyRepository, container.libraryCache, container.mosaicGenerator, container.remoteManager, container.playerStateManager) as T
}
