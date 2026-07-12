package com.crsmthw.lyra.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.data.local.CachedTrackList
import com.crsmthw.lyra.data.local.ForYouCacheData
import com.crsmthw.lyra.data.local.JumpBackInItem
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which content type the Library browser shows. */
enum class LibraryFilter { PLAYLISTS, ALBUMS, ARTISTS }

data class LibraryUiState(
    val playlists             : List<SpotifyPlaylist>  = emptyList(),
    val featuredPlaylists     : List<SpotifyPlaylist>  = emptyList(),
    val jumpBackIn            : List<JumpBackInItem>   = emptyList(),
    val topTracks             : List<SpotifyTrack>     = emptyList(),
    val libraryFilter         : LibraryFilter          = LibraryFilter.PLAYLISTS,
    val savedAlbums           : List<SpotifyAlbum>     = emptyList(),
    val followedArtists       : List<SpotifyArtist>    = emptyList(),
    val isLoadingCollections  : Boolean                = false,
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
                // Route through the single surgical cache method — same path as the picker toggle-off
                // and the player add — so the metadata count and both panes stay in sync. It emits
                // trackListChanges, which refreshes currentTracks via observeTrackListChanges, so no
                // manual list edit is needed here (one path, not two).
                withContext(Dispatchers.IO) {
                    cache.removeFromPlaylistTrackList(playlist.id, target.uri)
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
        observeCacheRevision()
        observeTrackListChanges()
    }

    /**
     * Live-refreshes the OPEN playlist's track list when its cached list changes from elsewhere —
     * e.g. adding/removing the current track via the full player or pop-out add-to-playlist sheet,
     * or the song-menu picker on another screen. Re-reads the (already surgically updated) cache so
     * the change shows without a manual pull-to-refresh. No-op unless the changed playlist is open.
     */
    private fun observeTrackListChanges() {
        viewModelScope.launch {
            cache.trackListChanges.collect { playlistId ->
                if (_uiState.value.currentPlaylist?.id != playlistId) return@collect
                val cached = withContext(Dispatchers.IO) { cache.loadTrackList(playlistId) } ?: return@collect
                _uiState.update { s ->
                    if (s.currentPlaylist?.id != playlistId) return@update s
                    // When the list was fully loaded, the cache size IS the new authoritative total
                    // (covers both add +1 and remove −1); for a partially-paged list keep the larger
                    // value so lazy-loading isn't cut short.
                    val wasFullyLoaded = s.playlistTracksOffset >= s.playlistTracksTotal
                    s.copy(
                        currentTracks        = cached.tracks,
                        playlistTracksOffset = cached.tracks.size,
                        playlistTracksTotal  = if (wasFullyLoaded) cached.tracks.size
                                               else maxOf(s.playlistTracksTotal, cached.tracks.size),
                    )
                }
            }
        }
    }

    /**
     * Re-syncs the in-memory playlist list from the cache whenever a playlist is created (or
     * deleted) from elsewhere — e.g. creating a new playlist from the song touch-and-hold menu or
     * the player's add-to-playlist sheet while this screen is already in memory. Without this the
     * new playlist only appears after a manual pull-to-refresh. The cache revision never fires on a
     * plain library save, so a normal refresh doesn't double-load.
     */
    private fun observeCacheRevision() {
        viewModelScope.launch {
            cache.revision.drop(1).collect {
                val cached = withContext(Dispatchers.IO) { cache.load() } ?: return@collect
                _uiState.update { it.copy(playlists = cached.playlists) }
            }
        }
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
                    jumpBackIn        = cached.forYou?.jumpBackIn.orEmpty(),
                    topTracks         = cached.forYou?.topTracks.orEmpty(),
                    savedAlbums       = cached.savedAlbums.orEmpty(),
                    followedArtists   = cached.followedArtists.orEmpty(),
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

            loadForYou()   // after playlists+featured so jump-back-in context lookup can resolve

            // Persist refreshed data, preserving existing track list + For-you cache
            val s = _uiState.value
            withContext(Dispatchers.IO) {
                val existing = cache.load()
                cache.save(LibraryCacheData(
                    playlists         = s.playlists,
                    featuredPlaylists = s.featuredPlaylists,
                    likedSongCount    = s.likedSongCount,
                    user              = s.user,
                    trackLists        = existing?.trackLists ?: emptyMap(),
                    forYou            = existing?.forYou,
                    savedAlbums       = existing?.savedAlbums,
                    followedArtists   = existing?.followedArtists,
                ))
            }
            // Generate mosaics for any new playlists that now have cached track lists
            generateMissingMosaicsAsync(s.playlists + s.featuredPlaylists, cache.load()?.trackLists ?: emptyMap())
        }
    }

    /**
     * Loads the "For you" band: jump-back-in (de-duped recently-played contexts) + short-term top
     * tracks. Runs after the playlist lists are in state so playlist contexts can resolve to a
     * name/art without extra calls; playlist contexts that aren't in the library are skipped
     * (naming them would cost a metadata call each). Failures keep the cached band silently.
     */
    private fun loadForYou() {
        viewModelScope.launch {
            repository.getRecentlyPlayed(limit = 50).onSuccess { resp ->
                val lookup = _uiState.value.playlists + _uiState.value.featuredPlaylists
                _uiState.update { it.copy(jumpBackIn = buildJumpBackIn(resp, lookup)) }
            }
            repository.getTopTracks(timeRange = "short_term", limit = 20).onSuccess { page ->
                _uiState.update { it.copy(topTracks = page.items) }
            }
            val s = _uiState.value
            if (s.jumpBackIn.isNotEmpty() || s.topTracks.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    cache.saveForYou(ForYouCacheData(jumpBackIn = s.jumpBackIn, topTracks = s.topTracks))
                }
            }
        }
    }

    private fun buildJumpBackIn(
        resp      : RecentlyPlayedResponse,
        playlists : List<SpotifyPlaylist>,
    ): List<JumpBackInItem> {
        val items = mutableListOf<JumpBackInItem>()
        val seen  = mutableSetOf<String>()
        for (h in resp.items.orEmpty()) {
            if (items.size >= MAX_JUMP_BACK_IN) break
            val ctx = h.context ?: continue
            val uri = ctx.uri ?: continue
            if (!seen.add(uri)) continue
            val id = ctx.contextId ?: continue
            when {
                // Liked Songs plays as the "collection" context (spotify:user:<id>:collection).
                uri.endsWith(":collection") ->
                    items += JumpBackInItem(type = "liked", id = "liked", uri = uri, title = "liked")
                ctx.type == "playlist" -> {
                    val pl = playlists.firstOrNull { it.id == id } ?: continue
                    items += JumpBackInItem(
                        type   = "playlist",
                        id     = pl.id,
                        uri    = pl.uri,
                        title  = pl.name,
                        artUrl = pl.thumbnailUrl.takeIf { it.isNotBlank() },
                    )
                }
                ctx.type == "album" -> {
                    val track = h.track ?: continue
                    val name  = track.album?.name ?: continue
                    items += JumpBackInItem(
                        type     = "album",
                        id       = id,
                        uri      = uri,
                        title    = name,
                        subtitle = track.allArtists,
                        artUrl   = track.artUrl.takeIf { it.isNotBlank() },
                    )
                }
                ctx.type == "artist" -> {
                    val track  = h.track ?: continue
                    val artist = track.artists?.firstOrNull() ?: continue
                    items += JumpBackInItem(
                        type   = "artist",
                        id     = artist.id,
                        uri    = uri,
                        title  = artist.name,
                        artUrl = track.artUrl.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return items
    }

    // ── Library filter (Playlists / Albums / Artists) ────────────────────────

    /** Once-per-session network refresh guard for the Albums/Artists filter content. */
    private var collectionsLoaded = false

    fun setLibraryFilter(filter: LibraryFilter) {
        _uiState.update { it.copy(libraryFilter = filter) }
        // Cached content (if any) is already in state from loadLibrary; refresh from the network
        // the first time either non-playlist filter is opened this session.
        if (filter != LibraryFilter.PLAYLISTS) loadCollections()
    }

    private fun loadCollections() {
        if (collectionsLoaded) return
        collectionsLoaded = true
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCollections = true) }

            // Saved albums — offset-paged.
            val albums = mutableListOf<SpotifyAlbum>()
            var offset = 0
            var albumsOk = true
            while (true) {
                val page = repository.getSavedAlbums(limit = 50, offset = offset).getOrNull()
                if (page == null) { albumsOk = albums.isNotEmpty(); break }
                val items = page.items.orEmpty()
                albums += items.mapNotNull { it.album }
                offset += items.size
                if (page.next == null || items.isEmpty()) break
            }
            if (albumsOk) _uiState.update { it.copy(savedAlbums = albums) }

            // Followed artists — cursor-paged, nested under "artists".
            val artists = mutableListOf<SpotifyArtist>()
            var after: String? = null
            var artistsOk = true
            while (true) {
                val page = repository.getFollowedArtists(after).getOrNull()?.artists
                if (page == null) { artistsOk = artists.isNotEmpty(); break }
                val items = page.items.orEmpty()
                artists += items
                after = page.cursors?.after
                if (after == null || items.isEmpty()) break
            }
            if (artistsOk) _uiState.update { it.copy(followedArtists = artists) }

            _uiState.update { it.copy(isLoadingCollections = false) }

            if (albumsOk || artistsOk) {
                val s = _uiState.value
                withContext(Dispatchers.IO) { cache.saveCollections(s.savedAlbums, s.followedArtists) }
            } else {
                collectionsLoaded = false   // both fetches failed with nothing cached — allow a retry
            }
        }
    }

    /** Plays the On-repeat row from [startIndex]: the tapped track first, the rest queued behind it. */
    fun playTopTrack(startIndex: Int) {
        val uris = _uiState.value.topTracks.drop(startIndex).map { it.uri }
        if (uris.isEmpty()) return
        playerStateManager.setOptimisticallyPlaying()
        viewModelScope.launch {
            repository.play(uris = uris).onFailure { e ->
                if (e.message?.contains("404") == true) {
                    remoteManager.connectAndPlay(uris.first())
                } else {
                    playerStateManager.releasePlayingOptimism()
                }
            }
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

            loadForYou()

            val s = _uiState.value
            withContext(Dispatchers.IO) {
                val existing = cache.load()
                cache.save(LibraryCacheData(
                    playlists         = s.playlists,
                    featuredPlaylists = s.featuredPlaylists,
                    likedSongCount    = s.likedSongCount,
                    user              = s.user,
                    trackLists        = existing?.trackLists ?: emptyMap(),
                    forYou            = existing?.forYou,
                    savedAlbums       = existing?.savedAlbums,
                    followedArtists   = existing?.followedArtists,
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

    /** Tracks the playlist the user most recently tapped, so the async cache read below can bail when
     *  a newer tap supersedes it (currentPlaylist isn't set until the detail is content-ready, so the
     *  usual `currentPlaylist?.id` in-flight guard can't be used during the cache read). */
    private var pendingSelectionId: String? = null

    fun selectPlaylist(playlist: SpotifyPlaylist) {
        if (_uiState.value.currentPlaylist?.id == playlist.id || pendingSelectionId == playlist.id) return
        // Read the cached tracks BEFORE flipping to the detail, so the browser→detail container
        // transform starts content-ready: no empty first frame, and no track emission landing
        // mid-transition (a state change during the crossfade is what blanks the screen). The browser
        // simply stays up for the ~ms disk read. Mirrors selectLikedSongs. A genuine cache miss flips
        // to the empty loading detail and the network fills it AFTER the transition (so it can't blank).
        pendingSelectionId = playlist.id
        viewModelScope.launch {
            val snapshotId = playlist.snapshotId
            val cached = if (snapshotId != null)
                withContext(Dispatchers.IO) { cache.loadTrackList(playlist.id) } else null
            if (pendingSelectionId != playlist.id) return@launch   // a newer selection superseded this

            if (cached != null && cached.snapshotId == snapshotId) {
                // Snapshot matches → cache is current. Flip to the detail in ONE content-ready emission
                // (tracks present from the transition's first frame); seed pagination from it (whatever
                // pages were loaded+cached before are preserved — no page-0 refetch that would clobber).
                _uiState.update { it.copy(
                    currentPlaylist      = playlist,
                    currentTracks        = cached.tracks,
                    isLoadingTracks      = false,
                    playlistTracksOffset = cached.tracks.size,
                    playlistTracksTotal  = maxOf(playlist.trackCount, cached.tracks.size),
                    error                = null,
                ) }
                if (playlist.id !in _uiState.value.playlistsWithMosaics && playlist.thumbnailUrl.isBlank()) {
                    withContext(Dispatchers.IO) { mosaicGenerator.generate(playlist.id, cached.tracks) }
                    _uiState.update { s -> s.copy(playlistsWithMosaics = s.playlistsWithMosaics + playlist.id) }
                }
                // Reconcile the authoritative total once per open (cheap limit=1): heals a drifted
                // count — an in-app add that Spotify's /me/playlists metadata hasn't caught up on, or a
                // change made on another device — without a manual refresh. maxOf with the loaded size
                // so a lagging server total can never drag the count below what's actually cached.
                repository.getPlaylistTracks(playlist.id, limit = 1, offset = 0).onSuccess { resp ->
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@onSuccess
                    val total = maxOf(resp.total, _uiState.value.currentTracks.size)
                    _uiState.update { it.copy(playlistTracksTotal = total) }
                    withContext(Dispatchers.IO) { cache.setPlaylistTrackCount(playlist.id, total) }
                }
                return@launch
            }

            // No cache or stale snapshot — flip to a loading detail (hero + spinner), then fetch the
            // first page. The fetch lands after the transition, so the empty start doesn't blank.
            _uiState.update { it.copy(
                currentPlaylist      = playlist,
                isLoadingTracks      = true,
                currentTracks        = emptyList(),
                playlistTracksOffset = 0,
                playlistTracksTotal  = playlist.trackCount,   // metadata total; refined from the response
                error                = null,
            ) }
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
                    if (_uiState.value.currentPlaylist?.id != playlist.id) return@fold
                    val msg = if (e.message?.contains("403") == true)
                        "Track list unavailable for this playlist. You can still play it with the ▶ button."
                    else e.message
                    _uiState.update { it.copy(error = msg, isLoadingTracks = false) }
                },
            )
        }
    }

    fun clearSelection() {
        pendingSelectionId = null   // cancel any in-flight playlist cache read
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
        // Already on Liked (no playlist + tracks loaded), or a Liked load already in flight → no-op.
        if ((_uiState.value.currentPlaylist == null && _uiState.value.currentTracks.isNotEmpty()) ||
            pendingSelectionId == LibraryCache.LIKED_SONGS_KEY) return
        // Read the liked cache BEFORE flipping to the list, so the swap starts content-ready — no empty
        // placeholder first frame and no track emission landing mid-transition (which blanks the
        // two-pane swap). The current view stays for the ~ms disk read. Mirrors selectPlaylist.
        pendingSelectionId = LibraryCache.LIKED_SONGS_KEY
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY) }
            if (pendingSelectionId != LibraryCache.LIKED_SONGS_KEY) return@launch   // superseded by a newer selection

            if (cached != null) {
                // Show cached tracks immediately — no loading spinner for the user.
                // distinctBy { it.id }: self-heal a cache that an older build corrupted with duplicate
                // liked songs (the filtered-offset pagination bug). Re-persisted below so the dupes
                // are gone on disk too — no clear-storage needed.
                val cachedCount   = cached.snapshotId.toIntOrNull() ?: cached.tracks.size
                val cachedTracks  = cached.tracks.distinctBy { it.id }
                _uiState.update { it.copy(
                    currentPlaylist     = null,
                    currentTracks       = cachedTracks,
                    isLoadingTracks     = false,
                    likedSongsOffset    = cachedTracks.size,
                    likedSongsTotal     = cachedCount,
                    isLoadingMoreTracks = false,
                    error               = null,
                )}
                if (cachedTracks.size != cached.tracks.size) {
                    withContext(Dispatchers.IO) {
                        cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, cachedCount.toString(), cachedTracks)
                    }
                }

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
                                        // distinctBy guards against an "external add" that's actually
                                        // a track already in the cache (e.g. re-like of an existing one).
                                        val merged = (newTracks + cachedTracks).distinctBy { it.id }
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

            // No cache — flip to a loading detail (Liked gradient hero + spinner), then fetch. The
            // network lands after the transition, so the empty start doesn't blank.
            _uiState.update { it.copy(
                currentPlaylist     = null,
                currentTracks       = emptyList(),
                isLoadingTracks     = true,
                likedSongsOffset    = 0,
                likedSongsTotal     = 0,
                isLoadingMoreTracks = false,
                error               = null,
            )}
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
                    // RAW page size, not filtered: see loadMoreLikedSongs. A null track on page 0
                    // would otherwise short the offset and overlap the next page.
                    likedSongsOffset = resp.items?.size ?: tracks.size,
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
        // Same initial-load race guard as loadMorePlaylistTracks (see there).
        if (s.isLoadingTracks || s.isLoadingMoreTracks || s.likedSongsOffset >= s.likedSongsTotal) return
        _uiState.update { it.copy(isLoadingMoreTracks = true) }
        viewModelScope.launch {
            repository.getLikedSongs(limit = 50, offset = s.likedSongsOffset).fold(
                onSuccess = { resp ->
                    val newTracks = (resp.items ?: emptyList()).mapNotNull { it.track }.filter { it.isPlayable != false }
                    // Advance the offset by the RAW page size, not the post-filter size: Spotify's
                    // offset indexes every saved item, including removed-from-Spotify tracks (null
                    // track) that mapNotNull drops. Advancing by the filtered size under-counts and
                    // makes the next page overlap → duplicate rows. distinctBy heals any overlap left
                    // from a cache-resume start offset. (Playlists already do this — see loadMorePlaylistTracks.)
                    val allTracks = (s.currentTracks + newTracks).distinctBy { it.id }
                    _uiState.update { it.copy(
                        currentTracks       = allTracks,
                        isLoadingMoreTracks = false,
                        likedSongsOffset    = s.likedSongsOffset + (resp.items?.size ?: 0),
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
        // isLoadingTracks guard: the TrackList auto-fires onLoadMore as soon as the (briefly empty)
        // list "reaches bottom", which collides with selectPlaylist's async cache load — loadMore
        // would fetch page 0 and append it onto the just-loaded cached list, doubling it (and
        // persisting the doubled list, so it compounds every open). Don't paginate until the initial
        // load has settled.
        if (s.isLoadingTracks || s.isLoadingMoreTracks || s.playlistTracksOffset >= s.playlistTracksTotal) return
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
                            // RAW page size, not filtered: see loadMoreLikedSongs.
                            likedSongsOffset = resp.items?.size ?: freshFirst50.size,
                            likedSongsTotal  = resp.total,
                            likedSongCount   = resp.total,
                        )}
                        withContext(Dispatchers.IO) {
                            // Preserve tracks cached beyond page 0 so background-fetch progress isn't lost.
                            // distinctBy heals any overlap between the fresh page 0 and the retained tail.
                            val beyond50 = cache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)?.tracks?.drop(50) ?: emptyList()
                            cache.saveTrackList(LibraryCache.LIKED_SONGS_KEY, resp.total.toString(), (freshFirst50 + beyond50).distinctBy { it.id })
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

/** Cap on "Jump back in" tiles — enough for two swipes of the row, cheap to build. */
private const val MAX_JUMP_BACK_IN = 10

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
