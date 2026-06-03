package com.crsmthw.lyra.data.repository

import com.crsmthw.lyra.data.local.LyraDataStore
import com.crsmthw.lyra.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

class SettingsRepository(private val dataStore: LyraDataStore) {

    val themeMode   : Flow<ThemeMode> = dataStore.themeMode
    val amoledBlack : Flow<Boolean>   = dataStore.amoledBlack
    val dynamicColor: Flow<Boolean>   = dataStore.dynamicColor
    val lyricsMode  : Flow<Boolean>   = dataStore.lyricsMode

    suspend fun setThemeMode   (mode   : ThemeMode) = dataStore.setThemeMode(mode)
    suspend fun setAmoledBlack (enabled: Boolean)   = dataStore.setAmoledBlack(enabled)
    suspend fun setDynamicColor(enabled: Boolean)   = dataStore.setDynamicColor(enabled)
    suspend fun setLyricsMode  (enabled: Boolean)   = dataStore.setLyricsMode(enabled)
}
