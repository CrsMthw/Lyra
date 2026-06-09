package com.crsmthw.lyra.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Top-level delegate – DataStore is a singleton per name
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyra_settings")

class LyraDataStore(private val context: Context) {

    // ── Reads ───────────────────────────────────────────────────────────────

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val amoledBlack: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AMOLED_BLACK] ?: false
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DYNAMIC_COLOR] ?: true
    }

    val lyricsMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LYRICS_MODE] ?: false
    }

    val visualizerEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_ENABLED] ?: false
    }

    val visualizerStyle: Flow<VisualizerStyle> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.VISUALIZER_STYLE] ?: VisualizerStyle.BOTH.name
        runCatching { VisualizerStyle.valueOf(raw) }.getOrDefault(VisualizerStyle.BOTH)
    }

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAPTICS_ENABLED] ?: true
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMOLED_BLACK] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLyricsMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LYRICS_MODE] = enabled }
    }

    suspend fun setVisualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VISUALIZER_ENABLED] = enabled }
    }

    suspend fun setVisualizerStyle(style: VisualizerStyle) {
        context.dataStore.edit { it[Keys.VISUALIZER_STYLE] = style.name }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    private object Keys {
        val THEME_MODE          = stringPreferencesKey("theme_mode")
        val AMOLED_BLACK        = booleanPreferencesKey("amoled_black")
        val DYNAMIC_COLOR       = booleanPreferencesKey("dynamic_color")
        val LYRICS_MODE         = booleanPreferencesKey("lyrics_mode")
        val VISUALIZER_ENABLED  = booleanPreferencesKey("visualizer_enabled")
        val VISUALIZER_STYLE    = stringPreferencesKey("visualizer_style")
        val HAPTICS_ENABLED     = booleanPreferencesKey("haptics_enabled")
    }
}
