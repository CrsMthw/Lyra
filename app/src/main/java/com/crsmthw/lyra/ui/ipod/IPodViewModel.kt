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
import com.crsmthw.lyra.ui.ipod.nav.LcdItem
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.nav.LcdListState
import com.crsmthw.lyra.ui.ipod.nav.LcdNavDirection
import com.crsmthw.lyra.ui.ipod.nav.LcdNowPlaying
import com.crsmthw.lyra.ui.ipod.wheel.WheelButton
import com.crsmthw.lyra.ui.ipod.wheel.WheelEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The iPod's brain: owns the LCD back stack and each entry's list, turns [WheelEvent]s into
 * navigation (MENU pops, SELECT pushes/activates, Scroll moves the highlight -- or scrubs on Now
 * Playing) and into [IPodEffect]s for anything that touches playback. Mirrors
 * `PlayerStateManager.state` into [IPodUiState.nowPlaying] and the iPod's own settings from
 * [SettingsRepository]. The transport buttons (PREVIOUS / NEXT / PLAY_PAUSE) are global: they
 * emit their effect whatever screen is showing.
 *
 * Reads the library cache-first (every read on Dispatchers.IO, once per screen entry -- never per
 * frame), gates every network call on `playerStateManager.isRateLimited()` and calls
 * `noteRateLimited()` on a 429, per docs/SPOTIFY.md.
 *
 * ### Recomposition
 *
 * The 1 Hz progress tick updates only [IPodUiState.nowPlaying]. The [stack] list instance and every
 * [IPodStackEntry] / [LcdListState] inside it are preserved across that update (the `copy` touches
 * only `nowPlaying`), so composables that read only the stack or its entries skip automatically --
 * they see the same reference and strong-skipping short-circuits on identity. No `@Stable`
 * annotation is needed as long as instances are never rebuilt gratuitously.
 */
class IPodViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryCache: LibraryCache,
    @Suppress("unused") // used in Checkpoint B for paged browsing
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) : ViewModel() {

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

    init {
        // Mirror the click-sounds setting into the UI state.
        viewModelScope.launch {
            settingsRepository.ipodClickSounds.collect { enabled ->
                _uiState.update { state ->
                    val newState = state.copy(clickSoundsEnabled = enabled)
                    // If the Settings screen is on top, rebuild its rows with the new value.
                    val top = newState.stack.lastOrNull()
                    if (top?.screen is IPodScreen.Settings) {
                        val updatedEntry = top.copy(
                            list = top.list.copy(items = buildSettingsItems(enabled)),
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
                    LcdNowPlaying(
                        title = track.name,
                        artist = track.allArtists,
                        album = track.album?.name ?: track.show?.name ?: "",
                        artUrl = track.artUrl,
                        isPlaying = ps.isPlaying,
                        progressMs = ps.progressMs,
                        durationMs = ps.durationMs,
                    )
                }
                .distinctUntilChanged()
                .collect { np ->
                    _uiState.update { state ->
                        // The stack is NEVER rebuilt here -- only nowPlaying changes.
                        // The main menu is rebuilt only when hasNowPlaying flips.
                        val hasNp = np != null
                        if (hasNp != lastHadNowPlaying) {
                            lastHadNowPlaying = hasNp
                            rebuildMainMenuIfOnTop(state.copy(nowPlaying = np), hasNp)
                        } else {
                            state.copy(nowPlaying = np)
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
        _uiState.update { state ->
            val top = state.current
            // On NowPlaying, scrolling is ignored in Checkpoint A (scrubbing comes in B).
            if (top.screen is IPodScreen.NowPlaying) return@update state
            val items = top.list.items
            if (items.isEmpty()) return@update state
            val newIndex = (top.list.selectedIndex + steps).coerceIn(0, items.lastIndex)
            if (newIndex == top.list.selectedIndex) return@update state
            val updatedEntry = top.copy(list = top.list.copy(selectedIndex = newIndex))
            state.copy(stack = state.stack.dropLast(1) + updatedEntry, direction = LcdNavDirection.NONE)
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
            if (state.stack.size <= 1) return@update state // root -- do nothing
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
            is IPodScreen.Music -> activateMusicItem(selected)
            is IPodScreen.Settings -> activateSettingsItem(selected.id)
            // Browse screens are empty in Checkpoint A; SELECT does nothing.
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
            "albums" -> push(
                IPodScreen.Albums,
                LcdLabel.Res(R.string.ipod_menu_albums),
            )
            "artists" -> push(
                IPodScreen.Artists,
                LcdLabel.Res(R.string.ipod_menu_artists),
            )
            "playlists" -> push(
                IPodScreen.Playlists,
                LcdLabel.Res(R.string.ipod_menu_playlists),
            )
            "podcasts" -> push(
                IPodScreen.Podcasts,
                LcdLabel.Res(R.string.ipod_menu_podcasts),
            )
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
                ?.distinctBy { it.id }
                ?.map { track ->
                    LcdItem(
                        id = track.uri,
                        title = LcdLabel.Text(track.name),
                        subtitle = LcdLabel.Text(track.allArtists),
                    )
                }
                ?: emptyList()

            updateTopEntry(IPodScreen.Music) { entry ->
                entry.copy(list = entry.list.copy(items = items, isLoading = false))
            }
        }
    }

    private fun activateMusicItem(item: LcdItem) {
        viewModelScope.launch {
            _effects.send(IPodEffect.PlayLikedSong(item.id))
        }
        push(
            IPodScreen.NowPlaying,
            LcdLabel.Res(R.string.ipod_menu_now_playing),
        )
    }

    // ── Shuffle Songs ─────────────────────────────────────────────────────────

    private fun handleShuffleSongs() {
        viewModelScope.launch {
            val userId = withContext(Dispatchers.IO) {
                libraryCache.load()?.user?.id?.takeIf { it.isNotBlank() }
            }
            if (userId == null) return@launch // no cached user -- gap (see knownGaps)
            _effects.send(IPodEffect.ShuffleContext("spotify:user:$userId:collection"))
            // Push NowPlaying only if not already there (double-SELECT guard).
            val state = _uiState.value
            if (state.current.screen !is IPodScreen.NowPlaying) {
                push(
                    IPodScreen.NowPlaying,
                    LcdLabel.Res(R.string.ipod_menu_now_playing),
                )
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun pushSettings() {
        val clickSounds = _uiState.value.clickSoundsEnabled
        push(
            screen = IPodScreen.Settings,
            title = LcdLabel.Res(R.string.ipod_menu_settings),
            items = buildSettingsItems(clickSounds),
        )
    }

    private fun activateSettingsItem(id: String) {
        when (id) {
            "clicksounds" -> {
                val current = _uiState.value.clickSoundsEnabled
                viewModelScope.launch { settingsRepository.setIpodClickSounds(!current) }
                // The flow collector above rebuilds the row's value.
            }
            "ipodmode" -> {
                viewModelScope.launch { settingsRepository.setIpodEnabled(false) }
                // MainActivity swaps the UI away; nothing else to do.
            }
        }
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
            val entry = IPodStackEntry(
                screen = screen,
                title = title,
                list = LcdListState(items = items, isLoading = loading),
            )
            state.copy(
                stack = state.stack + entry,
                direction = LcdNavDirection.FORWARD,
            )
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
        val updatedEntry = bottomEntry.copy(
            list = bottomEntry.list.copy(items = newItems, selectedIndex = newIndex),
        )
        // The main menu is always at index 0 in the stack.
        return state.copy(stack = listOf(updatedEntry) + state.stack.drop(1))
    }

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

        fun buildSettingsItems(clickSoundsEnabled: Boolean): List<LcdItem> = listOf(
            LcdItem(
                id = "clicksounds",
                title = LcdLabel.Res(R.string.ipod_settings_click_sounds),
                value = LcdLabel.Res(if (clickSoundsEnabled) R.string.ipod_value_on else R.string.ipod_value_off),
            ),
            LcdItem(
                id = "ipodmode",
                title = LcdLabel.Res(R.string.ipod_settings_mode),
                value = LcdLabel.Res(R.string.ipod_value_on),
            ),
        )
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
