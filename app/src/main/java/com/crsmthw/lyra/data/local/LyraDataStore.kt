package com.crsmthw.lyra.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    // Number of frequency bands the visualizer groups its FFT bins into (4..128, power of 2).
    // Lower = fewer, bigger, smoother waves; higher = sharp, high-resolution detail.
    val visualizerResolution: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_RESOLUTION] ?: 24
    }

    // true = RMS band grouping (dramatic, larger spikes); false = mean (even, smooth, ProjectM-style).
    val visualizerDramatic: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_DRAMATIC] ?: false
    }

    // Independent bottom-wave resolution, used only when style is BOTH and the sync toggle is off.
    val visualizerResolutionBottom: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_RESOLUTION_BOTTOM] ?: 24
    }
    val visualizerResolutionSync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_RESOLUTION_SYNC] ?: true
    }
    // Gain offset (-3..+3) per surface; 0 = the built-in base gain. Bottom used only when BOTH + unsynced.
    val visualizerGain: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_GAIN] ?: 0
    }
    val visualizerGainBottom: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_GAIN_BOTTOM] ?: 0
    }
    val visualizerGainSync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.VISUALIZER_GAIN_SYNC] ?: true
    }

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAPTICS_ENABLED] ?: true
    }

    // Default OFF: the "For you" band is opt-in — algorithmic resurfacing is the kind of
    // Spotify bloat Lyra exists to escape.
    val forYouEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FOR_YOU_ENABLED] ?: false
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

    suspend fun setVisualizerResolution(bands: Int) {
        context.dataStore.edit { it[Keys.VISUALIZER_RESOLUTION] = bands }
    }

    suspend fun setVisualizerDramatic(dramatic: Boolean) {
        context.dataStore.edit { it[Keys.VISUALIZER_DRAMATIC] = dramatic }
    }

    suspend fun setVisualizerResolutionBottom(bands: Int) {
        context.dataStore.edit { it[Keys.VISUALIZER_RESOLUTION_BOTTOM] = bands }
    }
    suspend fun setVisualizerResolutionSync(sync: Boolean) {
        context.dataStore.edit { it[Keys.VISUALIZER_RESOLUTION_SYNC] = sync }
    }
    suspend fun setVisualizerGain(offset: Int) {
        context.dataStore.edit { it[Keys.VISUALIZER_GAIN] = offset }
    }
    suspend fun setVisualizerGainBottom(offset: Int) {
        context.dataStore.edit { it[Keys.VISUALIZER_GAIN_BOTTOM] = offset }
    }
    suspend fun setVisualizerGainSync(sync: Boolean) {
        context.dataStore.edit { it[Keys.VISUALIZER_GAIN_SYNC] = sync }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setForYouEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FOR_YOU_ENABLED] = enabled }
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    private object Keys {
        val THEME_MODE          = stringPreferencesKey("theme_mode")
        val AMOLED_BLACK        = booleanPreferencesKey("amoled_black")
        val DYNAMIC_COLOR       = booleanPreferencesKey("dynamic_color")
        val LYRICS_MODE         = booleanPreferencesKey("lyrics_mode")
        val VISUALIZER_ENABLED  = booleanPreferencesKey("visualizer_enabled")
        val VISUALIZER_STYLE    = stringPreferencesKey("visualizer_style")
        val VISUALIZER_RESOLUTION = intPreferencesKey("visualizer_resolution")
        val VISUALIZER_DRAMATIC = booleanPreferencesKey("visualizer_dramatic")
        val VISUALIZER_RESOLUTION_BOTTOM = intPreferencesKey("visualizer_resolution_bottom")
        val VISUALIZER_RESOLUTION_SYNC = booleanPreferencesKey("visualizer_resolution_sync")
        val VISUALIZER_GAIN = intPreferencesKey("visualizer_gain")
        val VISUALIZER_GAIN_BOTTOM = intPreferencesKey("visualizer_gain_bottom")
        val VISUALIZER_GAIN_SYNC = booleanPreferencesKey("visualizer_gain_sync")
        val HAPTICS_ENABLED     = booleanPreferencesKey("haptics_enabled")
        val FOR_YOU_ENABLED     = booleanPreferencesKey("for_you_enabled")
    }
}
