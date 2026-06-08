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

    suspend fun setThemeMode        (mode   : ThemeMode)      = dataStore.setThemeMode(mode)
    suspend fun setAmoledBlack      (enabled: Boolean)        = dataStore.setAmoledBlack(enabled)
    suspend fun setDynamicColor     (enabled: Boolean)        = dataStore.setDynamicColor(enabled)
    suspend fun setLyricsMode       (enabled: Boolean)        = dataStore.setLyricsMode(enabled)
    suspend fun setVisualizerEnabled(enabled: Boolean)        = dataStore.setVisualizerEnabled(enabled)
    suspend fun setVisualizerStyle  (style  : VisualizerStyle) = dataStore.setVisualizerStyle(style)
}
