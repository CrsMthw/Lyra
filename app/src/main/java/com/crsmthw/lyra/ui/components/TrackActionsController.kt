package com.crsmthw.lyra.ui.components

import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.remote.model.AlbumTrack
import com.crsmthw.lyra.data.remote.model.SpotifyAlbum
import com.crsmthw.lyra.data.remote.model.SpotifyAlbumFull
import com.crsmthw.lyra.data.remote.model.SpotifyPlaylist
import com.crsmthw.lyra.data.remote.model.SpotifyTrack
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.ui.screens.player.AddToPlaylistResult
import com.crsmthw.lyra.ui.screens.player.PlaylistPickerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A track a long-press menu can act on, decoupled from any specific list model. */
data class TrackActionTarget(
    val id        : String,
    val uri       : String,
    val name      : String,
    val subtitle  : String,            // artist line shown in the sheet header
    val artUrl    : String,
    val albumId   : String?  = null,   // "Go to album" shown only when non-null
    val artistId  : String?  = null,   // "Go to artist" shown only when non-null
    val removable : RemovablePlaylist? = null, // "Remove from <name>" shown only when non-null
    val track     : SpotifyTrack? = null,      // full track, when known — lets add-to-playlist
                                               // surgically append to the playlist's cached list
)

/** The owned playlist the long-pressed row currently lives in, if any. */
data class RemovablePlaylist(val id: String, val name: String)

/** Maps a full [SpotifyTrack] (Library, Search, Artist top-tracks, Queue) to a menu target. */
fun SpotifyTrack.toTrackActionTarget(removable: RemovablePlaylist? = null) = TrackActionTarget(
    id        = id,
    uri       = uri,
    name      = name,
    subtitle  = allArtists,
    artUrl    = artUrl,
    albumId   = album?.id,
    artistId  = primaryArtistId,
    removable = removable,
    track     = this,
)

/**
 * Maps an [AlbumTrack] (album-detail rows carry no nested album) to a menu target, reconstructing a
 * full [SpotifyTrack] from the row + the album so add-to-playlist can surgically refresh the cached
 * playlist list. No "Go to album" — the user is already on it.
 */
fun AlbumTrack.toTrackActionTarget(album: SpotifyAlbumFull) = TrackActionTarget(
    id        = id,
    uri       = uri,
    name      = name,
    subtitle  = allArtists,
    artUrl    = album.artUrl,
    albumId   = null,
    artistId  = artists?.firstOrNull()?.id,
    track     = SpotifyTrack(
        id         = id,
        name       = name,
        uri        = uri,
        artists    = artists,
        album      = SpotifyAlbum(id = album.id, name = album.name, images = album.images, artists = album.artists),
        durationMs = durationMs,
        explicit   = explicit,
        isPlayable = isPlayable,
    ),
)

data class TrackActionsState(
    val target             : TrackActionTarget? = null,  // non-null = the actions sheet is open
    val isLiked            : Boolean?           = null,  // null = still resolving
    val showPlaylistPicker : Boolean           = false,
)

/**
 * Reusable backing for the song touch-and-hold menu. One instance per screen ViewModel
 * (`TrackActionsController(repository, libraryCache, viewModelScope)`); the screen renders a
 * single [TrackActionsHost] bound to it and calls [open] from each row's long-press.
 *
 * Reuses the player's [PlaylistPickerState]/[AddToPlaylistResult] (same types [com.crsmthw.lyra.ui.components.AddToPlaylistSheet]
 * already consumes) but, unlike `PlayerViewModel`, operates on an arbitrary [TrackActionTarget]
 * rather than the currently-playing track. Remove-from-playlist and navigation are intentionally
 * left to the screen (passed into [TrackActionsHost]) since only the screen owns that context.
 */
class TrackActionsController(
    private val repository  : SpotifyRepository,
    private val libraryCache: LibraryCache,
    private val scope       : CoroutineScope,
) {
    private val _state = MutableStateFlow(TrackActionsState())
    val state: StateFlow<TrackActionsState> = _state

    private val _pickerState = MutableStateFlow(PlaylistPickerState())
    val pickerState: StateFlow<PlaylistPickerState> = _pickerState

    fun open(target: TrackActionTarget) {
        _state.value = TrackActionsState(target = target)
        scope.launch {
            val liked = repository.isTrackSaved(target.id).getOrNull() ?: false
            _state.update { if (it.target?.id == target.id) it.copy(isLiked = liked) else it }
        }
    }

    fun dismiss() {
        _state.value = TrackActionsState()
        _pickerState.value = PlaylistPickerState()
    }

    /** Optimistic; the sheet stays open and reflects the new state, reverting on failure. */
    fun toggleLike() {
        val s = _state.value
        val t = s.target ?: return
        val liked = s.isLiked ?: return
        _state.update { if (it.target?.id == t.id) it.copy(isLiked = !liked) else it }
        scope.launch {
            val result = if (liked) repository.removeTrack(t.id) else repository.saveTrack(t.id)
            result.onFailure {
                _state.update { if (it.target?.id == t.id) it.copy(isLiked = liked) else it }
            }
        }
    }

    // ── Add-to-playlist picker (generalised from PlayerViewModel, targeting `state.target`) ──

    /**
     * Opens the add-to-playlist picker directly for [target], skipping the full actions sheet — used
     * by the player, which only wants add-to-playlist for the current track (no like / go-to / remove).
     */
    fun openPlaylistPickerFor(target: TrackActionTarget) {
        _state.value = TrackActionsState(target = target)
        openPlaylistPicker()
    }

    fun openPlaylistPicker() {
        val t = _state.value.target ?: return
        _state.update { it.copy(showPlaylistPicker = true) }
        _pickerState.value = PlaylistPickerState(isLoading = true)
        scope.launch {
            val userId = libraryCache.load()?.user?.id
                ?: repository.getCurrentUser().getOrNull()?.id
            val playlists = libraryCache.load()?.playlists?.takeIf { it.isNotEmpty() }
                ?: repository.getUserPlaylists().getOrNull()?.items
                ?: emptyList()
            val owned = if (userId != null) playlists.filter { it.owner.id == userId } else playlists
            val cacheData = libraryCache.load()
            val containing = owned.filter { playlist ->
                cacheData?.trackLists?.get(playlist.id)?.tracks?.any { it.id == t.id } == true
            }.map { it.id }.toSet()
            _pickerState.value = PlaylistPickerState(
                isLoading             = false,
                playlists             = owned,
                containingPlaylistIds = containing,
            )
        }
    }

    fun dismissPlaylistPicker() = dismiss()

    fun togglePlaylistTrack(playlist: SpotifyPlaylist) {
        val t = _state.value.target ?: return
        val isContained = playlist.id in _pickerState.value.containingPlaylistIds
        scope.launch {
            if (isContained) {
                repository.removeTrackFromPlaylist(playlist.id, t.uri).fold(
                    onSuccess = {
                        _pickerState.update { s ->
                            s.copy(
                                containingPlaylistIds = s.containingPlaylistIds - playlist.id,
                                addResult             = AddToPlaylistResult.Removed(playlist.name),
                            )
                        }
                        withContext(Dispatchers.IO) {
                            libraryCache.removeFromPlaylistTrackList(playlist.id, t.uri)
                        }
                    },
                    onFailure = { e -> _pickerState.update { it.copy(addResult = errorResult(e)) } },
                )
            } else {
                repository.addTrackToPlaylist(playlist.id, t.uri).fold(
                    onSuccess = {
                        _pickerState.update { s ->
                            s.copy(
                                containingPlaylistIds = s.containingPlaylistIds + playlist.id,
                                addResult             = AddToPlaylistResult.Added(playlist.name),
                            )
                        }
                        t.track?.let { full ->
                            withContext(Dispatchers.IO) {
                                libraryCache.appendToPlaylistTrackList(playlist.id, playlist.trackCount, full)
                            }
                        }
                    },
                    onFailure = { e -> _pickerState.update { it.copy(addResult = errorResult(e)) } },
                )
            }
        }
    }

    fun createPlaylist(name: String, description: String, isPublic: Boolean) {
        val t = _state.value.target ?: return
        _pickerState.update { it.copy(isCreatingPlaylist = true, createPlaylistError = null) }
        scope.launch {
            repository.createPlaylist(name, description, isPublic).fold(
                onSuccess = { playlist ->
                    // Add the track regardless of whether the add call itself succeeds — the
                    // playlist exists either way; mirror PlayerViewModel's behaviour.
                    val added = repository.addTrackToPlaylist(playlist.id, t.uri).isSuccess
                    libraryCache.prependPlaylist(playlist)
                    _pickerState.update { s ->
                        s.copy(
                            isCreatingPlaylist    = false,
                            playlists             = listOf(playlist) + s.playlists,
                            containingPlaylistIds = if (added) s.containingPlaylistIds + playlist.id
                                                    else s.containingPlaylistIds,
                            addResult             = AddToPlaylistResult.Added(playlist.name),
                        )
                    }
                },
                onFailure = { e ->
                    _pickerState.update { it.copy(isCreatingPlaylist = false, createPlaylistError = e.message) }
                },
            )
        }
    }

    fun clearPickerResult() {
        _pickerState.update { it.copy(addResult = null) }
    }

    private fun errorResult(e: Throwable): AddToPlaylistResult =
        if (e.message?.contains("403") == true) AddToPlaylistResult.NeedsReconnect
        else AddToPlaylistResult.Error(e.message)
}
