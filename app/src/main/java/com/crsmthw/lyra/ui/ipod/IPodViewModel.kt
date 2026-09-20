package com.crsmthw.lyra.ui.ipod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.player.PlayerStateManager
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.ui.ipod.nav.IPodScreen
import com.crsmthw.lyra.ui.ipod.nav.IPodStackEntry
import com.crsmthw.lyra.ui.ipod.nav.IPodUiState
import com.crsmthw.lyra.ui.ipod.nav.LcdLabel
import com.crsmthw.lyra.ui.ipod.wheel.WheelEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

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
 * STUB — the LCD lane implements this.
 */
class IPodViewModel(
    private val settingsRepository: SettingsRepository,
    private val libraryCache: LibraryCache,
    private val repository: SpotifyRepository,
    private val playerStateManager: PlayerStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        IPodUiState(
            stack = listOf(
                IPodStackEntry(IPodScreen.MainMenu, title = LcdLabel.Res(R.string.ipod_menu_title)),
            ),
        ),
    )
    val uiState: StateFlow<IPodUiState> = _uiState.asStateFlow()

    private val _effects = Channel<IPodEffect>(Channel.BUFFERED)
    /** One-shot requests for IPodRoot to route to the player. */
    val effects: Flow<IPodEffect> = _effects.receiveAsFlow()

    fun onWheelEvent(event: WheelEvent) { /* stub */ }
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
