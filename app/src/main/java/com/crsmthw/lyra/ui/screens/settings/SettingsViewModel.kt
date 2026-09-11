package com.crsmthw.lyra.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.crsmthw.lyra.data.local.EncryptedPrefs
import com.crsmthw.lyra.data.local.LibraryCache
import com.crsmthw.lyra.data.repository.SettingsRepository
import com.crsmthw.lyra.data.repository.SpotifyRepository
import com.crsmthw.lyra.di.AppContainer
import com.crsmthw.lyra.util.MosaicGenerator
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepo    : SettingsRepository,
    private val encryptedPrefs  : EncryptedPrefs,
    private val imageLoader     : ImageLoader,
    private val libraryCache    : LibraryCache,
    private val mosaicGenerator : MosaicGenerator,
    private val spotifyRepo     : SpotifyRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepo.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val amoledBlack: StateFlow<Boolean> = settingsRepo.amoledBlack
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val dynamicColor: StateFlow<Boolean> = settingsRepo.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val visualizerEnabled: StateFlow<Boolean> = settingsRepo.visualizerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val visualizerStyle: StateFlow<VisualizerStyle> = settingsRepo.visualizerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VisualizerStyle.BOTH)

    val visualizerResolution: StateFlow<Int> = settingsRepo.visualizerResolution
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 24)

    val visualizerDramatic: StateFlow<Boolean> = settingsRepo.visualizerDramatic
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val visualizerResolutionBottom: StateFlow<Int> = settingsRepo.visualizerResolutionBottom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 24)
    val visualizerResolutionSync: StateFlow<Boolean> = settingsRepo.visualizerResolutionSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val visualizerGain: StateFlow<Int> = settingsRepo.visualizerGain
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val visualizerGainBottom: StateFlow<Int> = settingsRepo.visualizerGainBottom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val visualizerGainSync: StateFlow<Boolean> = settingsRepo.visualizerGainSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val hapticsEnabled: StateFlow<Boolean> = settingsRepo.hapticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val forYouEnabled: StateFlow<Boolean> = settingsRepo.forYouEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _imageCacheBytes   = MutableStateFlow(0L)
    val imageCacheBytes: StateFlow<Long> = _imageCacheBytes

    private val _libraryCacheBytes = MutableStateFlow(0L)
    val libraryCacheBytes: StateFlow<Long> = _libraryCacheBytes

    init { refreshCacheSizes() }

    fun setThemeMode       (mode   : ThemeMode) { viewModelScope.launch { settingsRepo.setThemeMode(mode)            } }
    fun setAmoledBlack     (enabled: Boolean)   { viewModelScope.launch { settingsRepo.setAmoledBlack(enabled)        } }
    fun setDynamicColor    (enabled: Boolean)   { viewModelScope.launch { settingsRepo.setDynamicColor(enabled)       } }
    fun setVisualizerEnabled(enabled: Boolean)  { viewModelScope.launch { settingsRepo.setVisualizerEnabled(enabled)  } }
    fun setVisualizerStyle (style  : VisualizerStyle) { viewModelScope.launch { settingsRepo.setVisualizerStyle(style) } }
    fun setVisualizerResolution(bands: Int)     { viewModelScope.launch { settingsRepo.setVisualizerResolution(bands)  } }
    fun setVisualizerDramatic(dramatic: Boolean){ viewModelScope.launch { settingsRepo.setVisualizerDramatic(dramatic) } }
    fun setVisualizerResolutionBottom(bands: Int) { viewModelScope.launch { settingsRepo.setVisualizerResolutionBottom(bands) } }
    fun setVisualizerResolutionSync(sync: Boolean) { viewModelScope.launch { settingsRepo.setVisualizerResolutionSync(sync) } }
    fun setVisualizerGain(offset: Int)          { viewModelScope.launch { settingsRepo.setVisualizerGain(offset) } }
    fun setVisualizerGainBottom(offset: Int)    { viewModelScope.launch { settingsRepo.setVisualizerGainBottom(offset) } }
    fun setVisualizerGainSync(sync: Boolean)    { viewModelScope.launch { settingsRepo.setVisualizerGainSync(sync) } }
    fun setHapticsEnabled  (enabled: Boolean)   { viewModelScope.launch { settingsRepo.setHapticsEnabled(enabled)      } }
    fun setForYouEnabled   (enabled: Boolean)   { viewModelScope.launch { settingsRepo.setForYouEnabled(enabled)       } }

    /** Reset every visualizer setting to its default (surfaces=Both, 24 bands, gain 0, synced, mean). */
    fun resetVisualizerSettings() {
        viewModelScope.launch {
            settingsRepo.setVisualizerStyle(VisualizerStyle.BOTH)
            settingsRepo.setVisualizerResolution(24)
            settingsRepo.setVisualizerResolutionBottom(24)
            settingsRepo.setVisualizerResolutionSync(true)
            settingsRepo.setVisualizerGain(0)
            settingsRepo.setVisualizerGainBottom(0)
            settingsRepo.setVisualizerGainSync(true)
            settingsRepo.setVisualizerDramatic(false)
        }
    }

    fun clientIdMasked(): String {
        val id = encryptedPrefs.clientId
        return if (id.length > 8) id.take(4) + "••••" + id.takeLast(4)
        else if (id.isNotBlank()) "••••••••"
        else "Not set"
    }

    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
            _imageCacheBytes.value = 0L
        }
    }

    fun clearLibraryCache() {
        viewModelScope.launch(Dispatchers.IO) {
            libraryCache.clear()
            mosaicGenerator.dir.deleteRecursively()
            mosaicGenerator.dir.mkdirs()
            _libraryCacheBytes.value = 0L
        }
    }

    // ── TEMPORARY — podcast API spike, remove after go/no-go ─────────────────
    // Non-null while the spike dialog is up; the first emission is a placeholder line so the
    // dialog appears on the long-press instead of after the network round trips. Null also means
    // "dismissed", which is what gates the result write-back below.
    private val _spikeLog = MutableStateFlow<List<String>?>(null)
    val spikeLog: StateFlow<List<String>?> = _spikeLog

    private var spikeJob: Job? = null

    fun runPodcastSpike() {
        // Show the dialog FIRST, before the re-entrancy guard: if it was dismissed while a run is
        // still in flight, the gate below would throw that run's result away, so a second
        // long-press has to be able to re-open the dialog for the run already going.
        _spikeLog.value = listOf("Running podcast API spike…")
        if (spikeJob?.isActive == true) return   // a double long-press must not interleave the mutation legs
        spikeJob = viewModelScope.launch {
            val result = spotifyRepo.runPodcastSpike()
            // A dismissed dialog stays dismissed instead of popping back up over whatever the
            // tester moved on to. The run is deliberately NOT cancelled on dismiss: a cancel
            // landing between leg 4's PUT and DELETE would kill the undo and leave the library
            // dirty with no line anywhere. Nothing is lost either way — every line is also in
            // logcat under "PodcastSpike", including the !! LIBRARY LEFT DIRTY warning.
            // Safe unsynchronised: viewModelScope dispatches on Main.immediate and dismissSpike()
            // is called from the UI thread, so both touch the flow on the main thread.
            if (_spikeLog.value != null) _spikeLog.value = result
        }
    }

    fun dismissSpike() { _spikeLog.value = null }

    private fun refreshCacheSizes() {
        viewModelScope.launch(Dispatchers.IO) {
            _imageCacheBytes.value   = imageLoader.diskCache?.size ?: 0L
            _libraryCacheBytes.value = libraryCache.sizeBytes + mosaicGenerator.dir.walkTopDown().sumOf { it.length() }
        }
    }

}

class SettingsViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(
            container.settingsRepository,
            container.encryptedPrefs,
            container.imageLoader,
            container.libraryCache,
            container.mosaicGenerator,
            container.spotifyRepository,
        ) as T
}
