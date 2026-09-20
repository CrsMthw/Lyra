package com.crsmthw.lyra.ui.ipod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.ipod.nav.IPodScreen
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState
import com.crsmthw.lyra.ui.ipod.nav.LCD_ROWS_SINGLE_LINE
import com.crsmthw.lyra.ui.ipod.nav.LCD_ROWS_TWO_LINE
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.nav.LcdListState
import com.crsmthw.lyra.ui.ipod.nav.LcdNavDirection
import com.crsmthw.lyra.ui.ipod.nav.LcdNowPlaying
import com.crsmthw.lyra.ui.ipod.wheel.ClickPitch
import com.crsmthw.lyra.ui.ipod.wheel.ClickSoundsConfig
import com.crsmthw.lyra.ui.ipod.wheel.WheelButton
import com.crsmthw.lyra.ui.ipod.wheel.WheelEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Milliseconds before a scrub gesture commits to a seek. */
private const val SCRUB_COMMIT_MS = 350L

/** Paging trigger: fetch more when the highlight is within this many rows of the end. */
private const val PAGE_TRIGGER_ROWS = 8

/**
 * The iPod's brain: owns the LCD back stack and each entry's list, turns [WheelEvent]s into
 * navigation (MENU pops, SELECT pushes/activates, Scroll moves the highlight — or scrubs on Now
 * Playing) and into [IPodEffect]s for anything that touches playback. Mirrors
 * `PlayerStateManager.state` into [IPodUiState.nowPlaying] and the iPod's own settings from
 * [SettingsRepository]. The transport buttons (PREVIOUS / NEXT / PLAY_PAUSE) are global: they
 * emit their effect whatever screen is showing.
 *
 * Reads the library cache-first (every read on Dispatchers.IO, once per screen entry — never per
 * frame), gates every network call on `playerStateManager.isRateLimited()` and calls
 * `noteRateLimited()` on a 429, per docs/SPOTIFY.md.
 *
 * ### Window model (Checkpoint B)
 *
 * The list state carries `firstVisibleIndex` + `visibleRows`. The LCD draws rows
 * `[firstVisibleIndex, firstVisibleIndex + visibleRows)` with NO scrolling of its own, so the
 * ViewModel owns the window:
 * - `visibleRows` = TWO_LINE if any item has a subtitle, else SINGLE_LINE.
 * - Scroll: newSel = clamp(sel + steps); then the Classic rule — if the selection goes past the
 *   bottom of the window, the window scrolls down; above the top, up; otherwise unchanged.
 * - A MENU pop restores the popped-to entry EXACTLY as the user left it (no window mutation).
 * - Paging appends only touch `items` and `hasMore`, never the window or selection.
 *
 * ### Recomposition
 *
 * The 1 Hz progress tick updates only [IPodUiState.nowPlaying]. The [stack] list instance and
 * every [IPodStackEntry] / [LcdListState] inside it are preserved across that update (the `copy`
 * touches only `nowPlaying`), so composables that read only the stack or its entries skip
 * automatically. Scrub-in-progress fields are carried forward from the previous `nowPlaying`
 * during the tick, so the scrub position is never stomped by the player mirror.
 */
class IPodViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryCache: LibraryCache,
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) : ViewModel() {

    private val library = IPodLibrary(libraryCache, repository, playerStateManager)

    private val _uiState = MutableStateFlow(
        IPodUiState(
            stack = listOf(
                IPodStackEntry(
                    screen = IPodScreen.MainMenu,
                    title = LcdLabel.Res(R.string.ipod_menu_title),
                    list = LcdListState(items = buildMainMenu(hasNowPlaying = false)),
                ),
            ),
        ),
    )
    val uiState: StateFlow<IPodUiState> = _uiState.asStateFlow()

    private val _effects = Channel<IPodEffect>(Channel.BUFFERED)
    /** One-shot requests for IPodRoot to route to the player. */
    val effects: Flow<IPodEffect> = _effects.receiveAsFlow()

    /**
     * Tracks whether the main menu currently includes the "Now Playing" row. Rebuilt ONLY when
     * this flips (not on every 1 Hz tick), so the menu items list instance is stable across ticks.
     */
    private var lastHadNowPlaying = false

    /** The signed-in user's id, resolved once per iPod session for the Liked Songs collection URI. */
    private var cachedUserId: String? = null

    /**
     * Remembers the last track selection from a list so the Now Playing screen can show
     * "N of M" while that track is still playing.
     */
    private var lastSelection: TrackSelection? = null

    /** Scrub debounce job — cancelled and relaunched on every scrub step. */
    private var scrubJob: Job? = null

    /** Guards against duplicate fetch launches for paged browse screens. */
    private val pagingInFlight = mutableSetOf<String>()

    /**
     * The server-computed next offset for each paged screen, keyed by a string that uniquely
     * identifies the screen instance. Written from `IPodLibrary`'s `nextOffset` on every
     * successful page; read by [maybeTriggerPaging]. Never derived from `items.size` — the
     * rendered list is filtered (nulls, isPlayable, de-dup) so its length != the API offset.
     */
    private val nextOffsetByKey = mutableMapOf<String, Int>()

    init {
        // Mirror all three clicker settings (enabled, volume, pitch) into the UI state.
        viewModelScope.launch {
            combine(
                settingsRepository.ipodClickSounds,
                settingsRepository.ipodClickVolume,
                settingsRepository.ipodClickPitch,
            ) { enabled, volume, pitchOrdinal ->
                ClickSoundsConfig(
                    enabled = enabled,
                    volumePercent = volume.coerceIn(0, 100),
                    pitch = ClickPitch.entries.getOrElse(pitchOrdinal.coerceIn(0, ClickPitch.entries.lastIndex)) { ClickPitch.MEDIUM },
                )
            }.collect { config ->
                _uiState.update { state ->
                    val newState = state.copy(clickSounds = config)
                    // If the Settings screen is on top, rebuild its rows with the new values.
                    val top = newState.stack.lastOrNull()
                    if (top?.screen is IPodScreen.Settings) {
                        val updatedEntry = top.copy(
                            list = top.list.copy(items = buildSettingsItems(config)),
                        )
                        newState.copy(stack = newState.stack.dropLast(1) + updatedEntry)
                    } else {
                        newState
                    }
                }
            }
        }

        // Mirror the player state into nowPlaying, rebuilding the main menu only when
        // the presence of a playing track flips (not every second).
        viewModelScope.launch {
            playerStateManager.state
                .map { ps ->
                    val track = ps.currentTrack ?: return@map null
                    // Compute positionInList from the URI — the spec says "ONLY while
                    // currentTrack.uri == lastSelection.uri". Done here so `distinctUntilChanged`
                    // on the whole LcdNowPlaying still skips duplicates.
                    val sel = lastSelection
                    val posInList = if (sel != null && track.uri == sel.uri) sel.position else null
                    val listSize = if (posInList != null) sel?.listSize else null
                    LcdNowPlaying(
                        title = track.name,
                        artist = track.allArtists,
                        album = track.album?.name ?: track.show?.name ?: "",
                        artUrl = track.artUrl,
                        isPlaying = ps.isPlaying,
                        progressMs = ps.progressMs,
                        durationMs = ps.durationMs,
                        positionInList = posInList,
                        listSize = listSize,
                    )
                }
                .distinctUntilChanged()
                .collect { np ->
                    _uiState.update { state ->
                        // Carry forward scrub-in-progress from the old state so the 1 Hz tick
                        // cannot stomp a scrub in progress.
                        val enriched = if (np != null) {
                            val old = state.nowPlaying
                            np.copy(scrubProgressMs = old?.scrubProgressMs)
                        } else {
                            null
                        }

                        val hasNp = enriched != null
                        if (hasNp != lastHadNowPlaying) {
                            lastHadNowPlaying = hasNp
                            rebuildMainMenuIfOnTop(state.copy(nowPlaying = enriched), hasNp)
                        } else {
                            state.copy(nowPlaying = enriched)
                        }
                    }
                }
        }
    }

    // ── Wheel event dispatch ──────────────────────────────────────────────────

    fun onWheelEvent(event: WheelEvent) {
        when (event) {
            is WheelEvent.Scroll -> handleScroll(event.steps)
            is WheelEvent.Press -> handlePress(event.button)
        }
    }

    private fun handleScroll(steps: Int) {
        var shouldPage = false
        _uiState.update { state ->
            val top = state.current
            if (top.screen is IPodScreen.NowPlaying) {
                // Scrub: scroll on Now Playing adjusts the playback position.
                return@update computeScrub(state, steps)
            }
            val items = top.list.items
            if (items.isEmpty()) return@update state
            val visibleRows = top.list.visibleRows
            val newIndex = (top.list.selectedIndex + steps).coerceIn(0, items.lastIndex)
            if (newIndex == top.list.selectedIndex) return@update state

            // Classic window rule: selection drives the window, window never moves independently.
            var first = top.list.firstVisibleIndex
            if (newIndex >= first + visibleRows) {
                first = newIndex - visibleRows + 1
            } else if (newIndex < first) {
                first = newIndex
            }
            first = first.coerceIn(0, maxOf(0, items.size - visibleRows))

            val updatedEntry = top.copy(
                list = top.list.copy(selectedIndex = newIndex, firstVisibleIndex = first),
            )
            val newState = state.copy(stack = state.stack.dropLast(1) + updatedEntry)

            // Decide whether to page — the actual launch happens after update returns.
            shouldPage = shouldTriggerPaging(newState)

            newState
        }

        // Side effects AFTER the update — never inside the CAS lambda.
        if (shouldPage) triggerPaging()

        // If the last update produced a scrub, launch the debounce.
        launchScrubCommitIfNeeded()
    }

    /**
     * Compute the new state for a scrub step on Now Playing. Pure — no side effects.
     * The actual commit job is launched AFTER `_uiState.update` returns, via
     * [launchScrubCommitIfNeeded].
     */
    private fun computeScrub(state: IPodUiState, steps: Int): IPodUiState {
        val np = state.nowPlaying ?: return state
        val duration = np.durationMs
        if (duration <= 0) return state
        val step = maxOf(1_000L, duration / 100)
        val base = np.scrubProgressMs ?: np.progressMs
        val newScrub = (base + steps * step).coerceIn(0, duration)
        return state.copy(nowPlaying = np.copy(scrubProgressMs = newScrub))
    }

    /**
     * If a scrub is in progress (scrubProgressMs != null), cancel the old debounce and start
     * a new one. Called outside `_uiState.update` to avoid side effects in the CAS lambda.
     */
    private fun launchScrubCommitIfNeeded() {
        val scrubMs = _uiState.value.nowPlaying?.scrubProgressMs ?: return
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(SCRUB_COMMIT_MS)
            val currentDuration = _uiState.value.nowPlaying?.durationMs ?: return@launch
            if (currentDuration <= 0) return@launch
            _effects.send(IPodEffect.SeekTo(scrubMs.toFloat() / currentDuration.toFloat()))
            // Clear scrubProgressMs so the bar tracks the real position again.
            _uiState.update { s ->
                val curNp = s.nowPlaying ?: return@update s
                s.copy(nowPlaying = curNp.copy(scrubProgressMs = null))
            }
        }
    }

    private fun handlePress(button: WheelButton) {
        when (button) {
            WheelButton.MENU -> handleMenu()
            WheelButton.SELECT -> handleSelect()
            WheelButton.PLAY_PAUSE -> viewModelScope.launch { _effects.send(IPodEffect.PlayPause) }
            WheelButton.NEXT -> viewModelScope.launch { _effects.send(IPodEffect.Next) }
            WheelButton.PREVIOUS -> viewModelScope.launch { _effects.send(IPodEffect.Previous) }
        }
    }

    private fun handleMenu() {
        _uiState.update { state ->
            if (state.stack.size <= 1) return@update state // root — do nothing
            // Pop without mutating the entry we return to — preserves its window exactly.
            state.copy(
                stack = state.stack.dropLast(1),
                direction = LcdNavDirection.BACK,
            )
        }
    }

    private fun handleSelect() {
        val state = _uiState.value
        val top = state.current
        val items = top.list.items
        if (items.isEmpty()) return
        val selected = items.getOrNull(top.list.selectedIndex) ?: return

        when (top.screen) {
            is IPodScreen.MainMenu -> activateMainMenuItem(selected.id)
            is IPodScreen.Music -> activateMusicItem(selected, top.list.selectedIndex, items.size)
            is IPodScreen.Settings -> activateSettingsItem(selected.id)
            is IPodScreen.Albums -> activateAlbumItem(selected)
            is IPodScreen.AlbumTracks -> activateAlbumTrackItem(top.screen, selected, top.list.selectedIndex, items.size)
            is IPodScreen.Artists -> activateArtistItem(selected)
            is IPodScreen.ArtistAlbums -> activateArtistAlbumItem(selected)
            is IPodScreen.Playlists -> activatePlaylistItem(selected)
            is IPodScreen.PlaylistTracks -> activatePlaylistTrackItem(top.screen, selected, top.list.selectedIndex, items.size)
            is IPodScreen.Podcasts -> activateShowItem(selected)
            is IPodScreen.ShowEpisodes -> activateEpisodeItem(top.screen, selected, top.list, items)
            else -> {}
        }
    }

    // ── Main menu activation ──────────────────────────────────────────────────

    private fun activateMainMenuItem(id: String) {
        when (id) {
            "coverflow" -> push(
                IPodScreen.CoverFlow,
                LcdLabel.Res(R.string.ipod_menu_cover_flow),
            )
            "music" -> pushMusic()
            "albums" -> pushAlbums()
            "artists" -> pushArtists()
            "playlists" -> pushPlaylists()
            "podcasts" -> pushPodcasts()
            "shuffle" -> handleShuffleSongs()
            "nowplaying" -> push(
                IPodScreen.NowPlaying,
                LcdLabel.Res(R.string.ipod_menu_now_playing),
            )
            "settings" -> pushSettings()
        }
    }

    // ── Music (liked songs from cache) ────────────────────────────────────────

    private fun pushMusic() {
        push(
            screen = IPodScreen.Music,
            title = LcdLabel.Res(R.string.ipod_menu_music),
            loading = true,
        )
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                libraryCache.loadTrackList(LibraryCache.LIKED_SONGS_KEY)
            }
            val items = tracks?.tracks
                ?.distinctBy { it.uri }
                ?.map { track ->
                    LcdItem(
                        id = track.uri,
                        title = LcdLabel.Text(track.name),
                        subtitle = LcdLabel.Text(track.allArtists),
                    )
                }
                ?: emptyList()

            replaceTopItems(IPodScreen.Music, items)
        }
    }

    private fun activateMusicItem(item: LcdItem, index: Int, listSize: Int) {
        rememberSelection(item.id, index, listSize)
        viewModelScope.launch {
            _effects.send(IPodEffect.PlayLikedSong(item.id, shuffle = false))
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Albums ────────────────────────────────────────────────────────────────

    private fun pushAlbums() {
        push(
            screen = IPodScreen.Albums,
            title = LcdLabel.Res(R.string.ipod_menu_albums),
            loading = true,
        )
        viewModelScope.launch {
            library.savedAlbums().fold(
                onSuccess = { result ->
                    val items = result.albums.map { album ->
                        LcdItem(
                            id = album.id,
                            title = LcdLabel.Text(album.name),
                            subtitle = LcdLabel.Text(
                                album.artists?.joinToString(" · ") { it.name } ?: "",
                            ),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Albums, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Albums, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateAlbumItem(item: LcdItem) {
        val albumId = item.id
        val albumUri = "spotify:album:$albumId"
        push(
            screen = IPodScreen.AlbumTracks(albumId, albumUri),
            title = item.title,
            loading = true,
        )
        viewModelScope.launch {
            library.albumTracks(albumId).fold(
                onSuccess = { result ->
                    val items = result.tracks.map { track ->
                        LcdItem(
                            id = track.uri,
                            title = LcdLabel.Text(track.name),
                            subtitle = LcdLabel.Text(track.allArtists),
                        )
                    }
                    replaceTopItems(IPodScreen.AlbumTracks(albumId, albumUri), items)
                },
                onFailure = {
                    setTopError(
                        IPodScreen.AlbumTracks(albumId, albumUri),
                        LcdLabel.Res(R.string.ipod_vm_load_error),
                    )
                },
            )
        }
    }

    private fun activateAlbumTrackItem(
        screen: IPodScreen.AlbumTracks,
        item: LcdItem,
        index: Int,
        listSize: Int,
    ) {
        rememberSelection(item.id, index, listSize)
        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = item.id,
                    contextUri = screen.albumUri,
                    index = index,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Artists ───────────────────────────────────────────────────────────────

    private fun pushArtists() {
        push(
            screen = IPodScreen.Artists,
            title = LcdLabel.Res(R.string.ipod_menu_artists),
            loading = true,
        )
        viewModelScope.launch {
            library.followedArtists().fold(
                onSuccess = { result ->
                    val items = result.artists.map { artist ->
                        LcdItem(
                            id = artist.id,
                            title = LcdLabel.Text(artist.name),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Artists, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Artists, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateArtistItem(item: LcdItem) {
        val artistId = item.id
        push(
            screen = IPodScreen.ArtistAlbums(artistId),
            title = item.title,
            loading = true,
        )
        loadArtistAlbums(artistId, offset = 0)
    }

    private fun loadArtistAlbums(artistId: String, offset: Int) {
        val key = "artist_albums_$artistId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.artistAlbums(artistId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    val newItems = result.albums.map { album ->
                        val parts = listOfNotNull(
                            album.releaseYear.takeIf { it.isNotBlank() }?.let { LcdLabel.Text(it) },
                            album.albumType?.takeIf { it.isNotBlank() }
                                ?.let { LcdLabel.Text(it.replaceFirstChar { c -> c.uppercaseChar() }) },
                        )
                        LcdItem(
                            id = album.id,
                            title = LcdLabel.Text(album.name),
                            subtitle = if (parts.isEmpty()) null else LcdLabel.Joined(parts),
                            hasSubmenu = true,
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.ArtistAlbums(artistId),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.ArtistAlbums(artistId),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    private fun activateArtistAlbumItem(item: LcdItem) {
        // Push into the album's tracks.
        activateAlbumItem(item)
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    private fun pushPlaylists() {
        push(
            screen = IPodScreen.Playlists,
            title = LcdLabel.Res(R.string.ipod_menu_playlists),
            loading = true,
        )
        viewModelScope.launch {
            val userId = cachedUserId ?: library.resolveUserId().also { cachedUserId = it }
            if (userId == null) {
                setTopError(IPodScreen.Playlists, LcdLabel.Res(R.string.ipod_vm_load_error))
                return@launch
            }
            library.ownedPlaylists(userId).fold(
                onSuccess = { result ->
                    val items = result.playlists.map { playlist ->
                        LcdItem(
                            id = playlist.id,
                            title = LcdLabel.Text(playlist.name),
                            subtitle = LcdLabel.Plural(R.plurals.library_track_count, playlist.trackCount),
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Playlists, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Playlists, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activatePlaylistItem(item: LcdItem) {
        val playlistId = item.id
        val playlistUri = "spotify:playlist:$playlistId"
        push(
            screen = IPodScreen.PlaylistTracks(playlistId, playlistUri),
            title = item.title,
            loading = true,
        )
        loadPlaylistTracks(playlistId, playlistUri, offset = 0)
    }

    private fun loadPlaylistTracks(playlistId: String, playlistUri: String, offset: Int) {
        val key = "playlist_tracks_$playlistId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.playlistTracks(playlistId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    val newItems = result.tracks.map { track ->
                        LcdItem(
                            id = track.uri,
                            title = LcdLabel.Text(track.name),
                            subtitle = LcdLabel.Text(track.allArtists),
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.PlaylistTracks(playlistId, playlistUri),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.PlaylistTracks(playlistId, playlistUri),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    private fun activatePlaylistTrackItem(
        screen: IPodScreen.PlaylistTracks,
        item: LcdItem,
        index: Int,
        listSize: Int,
    ) {
        rememberSelection(item.id, index, listSize)
        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = item.id,
                    contextUri = screen.playlistUri,
                    index = index,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Podcasts ──────────────────────────────────────────────────────────────

    private fun pushPodcasts() {
        push(
            screen = IPodScreen.Podcasts,
            title = LcdLabel.Res(R.string.ipod_menu_podcasts),
            loading = true,
        )
        viewModelScope.launch {
            library.followedShows().fold(
                onSuccess = { result ->
                    val items = result.shows.mapNotNull { show ->
                        val id = show.id ?: return@mapNotNull null
                        val name = show.name ?: return@mapNotNull null
                        val episodeCount = show.totalEpisodes
                        val subtitle = episodeCount?.takeIf { it > 0 }
                            ?.let { LcdLabel.Plural(R.plurals.show_episode_count, it) }
                        LcdItem(
                            id = id,
                            title = LcdLabel.Text(name),
                            subtitle = subtitle,
                            hasSubmenu = true,
                        )
                    }
                    replaceTopItems(IPodScreen.Podcasts, items)
                },
                onFailure = {
                    setTopError(IPodScreen.Podcasts, LcdLabel.Res(R.string.ipod_vm_load_error))
                },
            )
        }
    }

    private fun activateShowItem(item: LcdItem) {
        val showId = item.id
        push(
            screen = IPodScreen.ShowEpisodes(showId),
            title = item.title,
            loading = true,
        )
        loadShowEpisodes(showId, offset = 0)
    }

    private fun loadShowEpisodes(showId: String, offset: Int) {
        val key = "show_episodes_$showId"
        if (key in pagingInFlight) return
        pagingInFlight += key
        viewModelScope.launch {
            library.showEpisodes(showId, offset).fold(
                onSuccess = { result ->
                    nextOffsetByKey[key] = result.nextOffset
                    val newItems = result.episodes.map { ep ->
                        LcdItem(
                            id = ep.id,
                            title = LcdLabel.Text(ep.name),
                            subtitle = ep.subtitle,
                        )
                    }
                    appendOrReplaceTopItems(
                        IPodScreen.ShowEpisodes(showId),
                        newItems,
                        result.hasMore,
                        isFirstPage = offset == 0,
                    )
                    // Stash the episode data for playback.
                    stashEpisodeData(showId, result.episodes, offset == 0)
                },
                onFailure = {
                    if (offset == 0) {
                        setTopError(
                            IPodScreen.ShowEpisodes(showId),
                            LcdLabel.Res(R.string.ipod_vm_load_error),
                        )
                    }
                },
            )
            pagingInFlight -= key
        }
    }

    /** Episode data stashed for queue building and resume point access. */
    private val episodeDataByShow = mutableMapOf<String, MutableList<IPodLibrary.EpisodeItem>>()

    private fun stashEpisodeData(showId: String, episodes: List<IPodLibrary.EpisodeItem>, isFirstPage: Boolean) {
        if (isFirstPage) {
            episodeDataByShow[showId] = episodes.toMutableList()
        } else {
            val existing = episodeDataByShow.getOrPut(showId) { mutableListOf() }
            val existingIds = existing.map { it.id }.toSet()
            existing += episodes.filter { it.id !in existingIds }
        }
    }

    private fun activateEpisodeItem(
        screen: IPodScreen.ShowEpisodes,
        item: LcdItem,
        listState: LcdListState,
        items: List<LcdItem>,
    ) {
        val episodeId = item.id
        val episodes = episodeDataByShow[screen.showId] ?: emptyList()
        val episode = episodes.find { it.id == episodeId }
        val uri = episode?.uri ?: "spotify:episode:$episodeId"

        rememberSelection(uri, listState.selectedIndex, items.size)

        val queue = library.buildEpisodeQueue(episodes, episodeId)

        viewModelScope.launch {
            _effects.send(
                IPodEffect.PlayTrack(
                    uri = uri,
                    uris = queue,
                    startPositionMs = episode?.resumePositionMs,
                    shuffle = false,
                ),
            )
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Shuffle Songs ─────────────────────────────────────────────────────────

    private fun handleShuffleSongs() {
        viewModelScope.launch {
            val userId = cachedUserId ?: library.resolveUserId().also { cachedUserId = it }
            if (userId != null) {
                _effects.send(IPodEffect.ShuffleContext("spotify:user:$userId:collection"))
            }
            // The wheel has already clicked, so the LCD must move either way: with no user id
            // Now Playing shows its empty state rather than the menu sitting inert.
            if (_uiState.value.current.screen !is IPodScreen.NowPlaying) {
                push(
                    IPodScreen.NowPlaying,
                    LcdLabel.Res(R.string.ipod_menu_now_playing),
                )
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun pushSettings() {
        val config = _uiState.value.clickSounds
        push(
            screen = IPodScreen.Settings,
            title = LcdLabel.Res(R.string.ipod_menu_settings),
            items = buildSettingsItems(config),
        )
    }

    private fun activateSettingsItem(id: String) {
        when (id) {
            "clicksounds" -> {
                val current = _uiState.value.clickSounds.enabled
                viewModelScope.launch { settingsRepository.setIpodClickSounds(!current) }
            }
            "clickvolume" -> {
                val current = _uiState.value.clickSounds.volumePercent
                val next = when {
                    current < 50 -> 50
                    current < 75 -> 75
                    current < 100 -> 100
                    else -> 25
                }
                viewModelScope.launch { settingsRepository.setIpodClickVolume(next) }
            }
            "clickpitch" -> {
                val current = _uiState.value.clickSounds.pitch
                val nextOrdinal = (current.ordinal + 1) % ClickPitch.entries.size
                viewModelScope.launch { settingsRepository.setIpodClickPitch(nextOrdinal) }
            }
            "ipodmode" -> {
                viewModelScope.launch { settingsRepository.setIpodEnabled(false) }
            }
        }
    }

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Pure check: returns true if the top screen should fetch more. Called inside update. */
    private fun shouldTriggerPaging(state: IPodUiState): Boolean {
        val top = state.current
        if (!top.list.hasMore) return false
        val remaining = top.list.items.size - top.list.selectedIndex - 1
        return remaining <= PAGE_TRIGGER_ROWS
    }

    /** Launch the actual paging fetch. Called OUTSIDE `_uiState.update`. */
    private fun triggerPaging() {
        val top = _uiState.value.current
        when (val screen = top.screen) {
            is IPodScreen.ArtistAlbums -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadArtistAlbums(screen.artistId, offset)
            }
            is IPodScreen.PlaylistTracks -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadPlaylistTracks(screen.playlistId, screen.playlistUri, offset)
            }
            is IPodScreen.ShowEpisodes -> {
                val key = pagingKey(screen)
                val offset = nextOffsetByKey[key] ?: return
                loadShowEpisodes(screen.showId, offset)
            }
            else -> {}
        }
    }

    /** Unique key for paging state tracking. */
    private fun pagingKey(screen: IPodScreen): String = when (screen) {
        is IPodScreen.ArtistAlbums -> "artist_albums_${screen.artistId}"
        is IPodScreen.PlaylistTracks -> "playlist_tracks_${screen.playlistId}"
        is IPodScreen.ShowEpisodes -> "show_episodes_${screen.showId}"
        else -> ""
    }

    // ── Track selection memory ────────────────────────────────────────────────

    private data class TrackSelection(
        val uri: String,
        /** 1-based position in the list. */
        val position: Int,
        val listSize: Int,
    )

    private fun rememberSelection(uri: String, index: Int, listSize: Int) {
        lastSelection = TrackSelection(
            uri = uri,
            position = index + 1,
            listSize = listSize,
        )
    }

    // ── Stack helpers ─────────────────────────────────────────────────────────

    /**
     * Push a new screen onto the stack. Guards against pushing the same screen twice from a
     * double-SELECT (the Classic ignores it).
     */
    private fun push(
        screen: IPodScreen,
        title: LcdLabel,
        items: List<LcdItem> = emptyList(),
        loading: Boolean = false,
    ) {
        _uiState.update { state ->
            if (state.current.screen == screen) return@update state
            val visibleRows = computeVisibleRows(items)
            val entry = IPodStackEntry(
                screen = screen,
                title = title,
                list = LcdListState(
                    items = items,
                    isLoading = loading,
                    visibleRows = visibleRows,
                ),
            )
            state.copy(
                stack = state.stack + entry,
                direction = LcdNavDirection.FORWARD,
            )
        }
    }

    /**
     * Replace the items on the top entry, setting the appropriate `visibleRows` and clamping
     * the window. Used when a screen's data arrives asynchronously.
     */
    private fun replaceTopItems(
        expectedScreen: IPodScreen,
        items: List<LcdItem>,
        hasMore: Boolean = false,
    ) {
        updateTopEntry(expectedScreen) { entry ->
            val visibleRows = computeVisibleRows(items)
            val sel = entry.list.selectedIndex.coerceIn(0, maxOf(0, items.lastIndex))
            val first = entry.list.firstVisibleIndex.coerceIn(0, maxOf(0, items.size - visibleRows))
            entry.copy(
                list = entry.list.copy(
                    items = items,
                    isLoading = false,
                    error = null,
                    visibleRows = visibleRows,
                    selectedIndex = sel,
                    firstVisibleIndex = first,
                    hasMore = hasMore,
                ),
            )
        }
    }

    /**
     * Append new items to the top entry (paging) or replace if this is the first page.
     * Never moves the selection or window — only items and hasMore change.
     */
    private fun appendOrReplaceTopItems(
        expectedScreen: IPodScreen,
        newItems: List<LcdItem>,
        hasMore: Boolean,
        isFirstPage: Boolean,
    ) {
        updateTopEntry(expectedScreen) { entry ->
            val combined = if (isFirstPage) {
                newItems
            } else {
                // De-dup by id to handle overlaps at page boundaries.
                val existingIds = entry.list.items.map { it.id }.toSet()
                entry.list.items + newItems.filter { it.id !in existingIds }
            }
            val visibleRows = computeVisibleRows(combined)
            val sel = if (isFirstPage) 0 else entry.list.selectedIndex.coerceIn(0, maxOf(0, combined.lastIndex))
            val first = if (isFirstPage) 0 else entry.list.firstVisibleIndex.coerceIn(0, maxOf(0, combined.size - visibleRows))
            entry.copy(
                list = entry.list.copy(
                    items = combined,
                    isLoading = false,
                    error = null,
                    visibleRows = visibleRows,
                    selectedIndex = sel,
                    firstVisibleIndex = first,
                    hasMore = hasMore,
                ),
            )
        }
    }

    /** Set an error on the top entry. */
    private fun setTopError(expectedScreen: IPodScreen, error: LcdLabel) {
        updateTopEntry(expectedScreen) { entry ->
            entry.copy(list = entry.list.copy(isLoading = false, error = error))
        }
    }

    /**
     * Update the top entry of the stack if it matches the expected screen. Safe against a race
     * where the user has already backed out by the time the async load finishes.
     */
    private fun updateTopEntry(
        expectedScreen: IPodScreen,
        transform: (IPodStackEntry) -> IPodStackEntry,
    ) {
        _uiState.update { state ->
            val top = state.stack.lastOrNull() ?: return@update state
            if (top.screen != expectedScreen) return@update state
            state.copy(stack = state.stack.dropLast(1) + transform(top))
        }
    }

    /**
     * Rebuild the main menu when the now-playing presence flips, preserving the highlight on the
     * same item id. Only called from the player-state collector when [hasNowPlaying] changes.
     */
    private fun rebuildMainMenuIfOnTop(state: IPodUiState, hasNowPlaying: Boolean): IPodUiState {
        val bottomEntry = state.stack.firstOrNull() ?: return state
        if (bottomEntry.screen !is IPodScreen.MainMenu) return state
        val oldItems = bottomEntry.list.items
        val oldSelectedId = oldItems.getOrNull(bottomEntry.list.selectedIndex)?.id
        val newItems = buildMainMenu(hasNowPlaying)
        val newIndex = if (oldSelectedId != null) {
            newItems.indexOfFirst { it.id == oldSelectedId }.coerceAtLeast(0)
        } else {
            0
        }
        val visibleRows = computeVisibleRows(newItems)
        var first = bottomEntry.list.firstVisibleIndex
        // Clamp the window for the new list.
        if (newIndex >= first + visibleRows) first = newIndex - visibleRows + 1
        if (newIndex < first) first = newIndex
        first = first.coerceIn(0, maxOf(0, newItems.size - visibleRows))

        val updatedEntry = bottomEntry.copy(
            list = bottomEntry.list.copy(
                items = newItems,
                selectedIndex = newIndex,
                firstVisibleIndex = first,
                visibleRows = visibleRows,
            ),
        )
        // The main menu is always at index 0 in the stack.
        return state.copy(stack = listOf(updatedEntry) + state.stack.drop(1))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Determines the row count: TWO_LINE if any item has a subtitle, SINGLE_LINE otherwise. */
    private fun computeVisibleRows(items: List<LcdItem>): Int =
        if (items.any { it.subtitle != null }) LCD_ROWS_TWO_LINE else LCD_ROWS_SINGLE_LINE

    // ── Menu builders ─────────────────────────────────────────────────────────

    companion object {
        fun buildMainMenu(hasNowPlaying: Boolean): List<LcdItem> = buildList {
            add(LcdItem(id = "coverflow", title = LcdLabel.Res(R.string.ipod_menu_cover_flow), hasSubmenu = true))
            add(LcdItem(id = "music", title = LcdLabel.Res(R.string.ipod_menu_music), hasSubmenu = true))
            add(LcdItem(id = "albums", title = LcdLabel.Res(R.string.ipod_menu_albums), hasSubmenu = true))
            add(LcdItem(id = "artists", title = LcdLabel.Res(R.string.ipod_menu_artists), hasSubmenu = true))
            add(LcdItem(id = "playlists", title = LcdLabel.Res(R.string.ipod_menu_playlists), hasSubmenu = true))
            add(LcdItem(id = "podcasts", title = LcdLabel.Res(R.string.ipod_menu_podcasts), hasSubmenu = true))
            add(LcdItem(id = "shuffle", title = LcdLabel.Res(R.string.ipod_menu_shuffle_songs)))
            if (hasNowPlaying) {
                add(LcdItem(id = "nowplaying", title = LcdLabel.Res(R.string.ipod_menu_now_playing), hasSubmenu = true))
            }
            add(LcdItem(id = "settings", title = LcdLabel.Res(R.string.ipod_menu_settings), hasSubmenu = true))
        }

        fun buildSettingsItems(config: ClickSoundsConfig): List<LcdItem> = listOf(
            LcdItem(
                id = "clicksounds",
                title = LcdLabel.Res(R.string.ipod_settings_click_sounds),
                value = LcdLabel.Res(if (config.enabled) R.string.ipod_value_on else R.string.ipod_value_off),
            ),
            LcdItem(
                id = "clickvolume",
                title = LcdLabel.Res(R.string.ipod_settings_click_volume),
                value = LcdLabel.ResArgs(R.string.ipod_value_percent, listOf(config.volumePercent)),
            ),
            LcdItem(
                id = "clickpitch",
                title = LcdLabel.Res(R.string.ipod_settings_click_pitch),
                value = LcdLabel.Res(
                    when (config.pitch) {
                        ClickPitch.LOW -> R.string.ipod_value_low
                        ClickPitch.MEDIUM -> R.string.ipod_value_medium
                        ClickPitch.HIGH -> R.string.ipod_value_high
                    },
                ),
            ),
            LcdItem(
                id = "ipodmode",
                title = LcdLabel.Res(R.string.ipod_settings_mode),
                value = LcdLabel.Res(R.string.ipod_value_on),
            ),
        )

        /** Overload for backward compat with the old signature. */
        fun buildSettingsItems(clickSoundsEnabled: Boolean): List<LcdItem> =
            buildSettingsItems(ClickSoundsConfig(enabled = clickSoundsEnabled))
    }
}

class IPodViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        IPodViewModel(
            settingsRepository = container.settingsRepository,
            libraryCache       = container.libraryCache,
            repository         = container.spotifyRepository,
            playerStateManager = container.playerStateManager,
        ) as T
}
