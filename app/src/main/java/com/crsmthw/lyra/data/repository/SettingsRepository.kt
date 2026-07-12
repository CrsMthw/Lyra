package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.local.LyraDataStore
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dataStore: LyraDataStore) {

    val themeMode        : Flow<ThemeMode>      = dataStore.themeMode
    val amoledBlack      : Flow<Boolean>        = dataStore.amoledBlack
    val dynamicColor     : Flow<Boolean>        = dataStore.dynamicColor
    val lyricsMode       : Flow<Boolean>        = dataStore.lyricsMode
    val visualizerEnabled: Flow<Boolean>        = dataStore.visualizerEnabled
    val visualizerStyle  : Flow<VisualizerStyle> = dataStore.visualizerStyle
    val visualizerResolution: Flow<Int>         = dataStore.visualizerResolution
    val visualizerDramatic: Flow<Boolean>       = dataStore.visualizerDramatic
    val visualizerResolutionBottom: Flow<Int>   = dataStore.visualizerResolutionBottom
    val visualizerResolutionSync: Flow<Boolean> = dataStore.visualizerResolutionSync
    val visualizerGain: Flow<Int>               = dataStore.visualizerGain
    val visualizerGainBottom: Flow<Int>         = dataStore.visualizerGainBottom
    val visualizerGainSync: Flow<Boolean>       = dataStore.visualizerGainSync
    val hapticsEnabled   : Flow<Boolean>        = dataStore.hapticsEnabled
    val forYouEnabled    : Flow<Boolean>        = dataStore.forYouEnabled

    suspend fun setThemeMode        (mode   : ThemeMode)      = dataStore.setThemeMode(mode)
    suspend fun setAmoledBlack      (enabled: Boolean)        = dataStore.setAmoledBlack(enabled)
    suspend fun setDynamicColor     (enabled: Boolean)        = dataStore.setDynamicColor(enabled)
    suspend fun setLyricsMode       (enabled: Boolean)        = dataStore.setLyricsMode(enabled)
    suspend fun setVisualizerEnabled(enabled: Boolean)        = dataStore.setVisualizerEnabled(enabled)
    suspend fun setVisualizerStyle  (style  : VisualizerStyle) = dataStore.setVisualizerStyle(style)
    suspend fun setVisualizerResolution(bands: Int)           = dataStore.setVisualizerResolution(bands)
    suspend fun setVisualizerDramatic(dramatic: Boolean)      = dataStore.setVisualizerDramatic(dramatic)
    suspend fun setVisualizerResolutionBottom(bands: Int)     = dataStore.setVisualizerResolutionBottom(bands)
    suspend fun setVisualizerResolutionSync(sync: Boolean)    = dataStore.setVisualizerResolutionSync(sync)
    suspend fun setVisualizerGain(offset: Int)                = dataStore.setVisualizerGain(offset)
    suspend fun setVisualizerGainBottom(offset: Int)          = dataStore.setVisualizerGainBottom(offset)
    suspend fun setVisualizerGainSync(sync: Boolean)          = dataStore.setVisualizerGainSync(sync)
    suspend fun setHapticsEnabled   (enabled: Boolean)        = dataStore.setHapticsEnabled(enabled)
    suspend fun setForYouEnabled    (enabled: Boolean)        = dataStore.setForYouEnabled(enabled)
}
