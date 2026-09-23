package com.crsmthw.lyra.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crsmthw.lyra.ui.cassette.CASSETTE_DEFAULT_CUSTOM_COLOR
import com.crsmthw.lyra.ui.cassette.CassetteColorSource
import com.crsmthw.lyra.ui.cassette.CassetteSettings
import com.crsmthw.lyra.ui.theme.ThemeMode
import com.crsmthw.lyra.util.visualizer.VisualizerStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    // ── iLyra mode (easter egg) ───────────────────────────────────────────────
    // `ilyraUnlocked` is set once by the 5-tap on the Settings version line and never cleared;
    // it only gates whether the "iLyra" row is visible. `ilyraEnabled` swaps the whole UI for the
    // Classic recreation (MainActivity branches on it, before the first frame). Click
    // sounds are the Classic's own setting, independent of the Lyra haptics toggle.
    val ilyraUnlocked: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_UNLOCKED] ?: false
    }
    val ilyraEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_ENABLED] ?: false
    }
    val ilyraClickSounds: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_CLICK_SOUNDS] ?: true
    }
    /** 0..100, default 50 — the first clicker was too loud on the Fold. */
    val ilyraClickVolume: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_CLICK_VOLUME] ?: 50
    }
    /** ClickPitch ordinal: 0 low, 1 medium (default), 2 high. */
    val ilyraClickPitch: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_CLICK_PITCH] ?: 1
    }
    /** ILyraBodyColor ordinal: 0 silver (default), 1 black. */
    val ilyraBodyColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.ILYRA_BODY_COLOR] ?: 0
    }

    /**
     * The cassette idle screen — every preference in ONE snapshot so the player collects one flow.
     * Defaults: off, no keep-awake, no dim, album-art colour, the ad's purple, hint shown.
     */
    val cassetteSettings: Flow<CassetteSettings> = context.dataStore.data.map { prefs ->
        CassetteSettings(
            enabled       = prefs[Keys.CASSETTE_ENABLED] ?: false,
            keepScreenOn  = prefs[Keys.CASSETTE_KEEP_SCREEN_ON] ?: false,
            dimAfterDelay = prefs[Keys.CASSETTE_DIM] ?: false,
            colorSource   = prefs[Keys.CASSETTE_COLOR_SOURCE]
                ?.let { raw -> runCatching { CassetteColorSource.valueOf(raw) }.getOrNull() }
                ?: CassetteColorSource.ALBUM_ART,
            customColor   = prefs[Keys.CASSETTE_CUSTOM_COLOR] ?: CASSETTE_DEFAULT_CUSTOM_COLOR,
            showExitHint  = prefs[Keys.CASSETTE_SHOW_EXIT_HINT] ?: true,
        )
    }.distinctUntilChanged()

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

    suspend fun setIlyraUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[Keys.ILYRA_UNLOCKED] = unlocked }
    }

    suspend fun setIlyraEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ILYRA_ENABLED] = enabled }
    }

    suspend fun setIlyraClickSounds(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ILYRA_CLICK_SOUNDS] = enabled }
    }

    suspend fun setIlyraClickVolume(percent: Int) {
        context.dataStore.edit { it[Keys.ILYRA_CLICK_VOLUME] = percent.coerceIn(0, 100) }
    }

    suspend fun setIlyraClickPitch(ordinal: Int) {
        context.dataStore.edit { it[Keys.ILYRA_CLICK_PITCH] = ordinal }
    }

    suspend fun setIlyraBodyColor(ordinal: Int) {
        context.dataStore.edit { it[Keys.ILYRA_BODY_COLOR] = ordinal }
    }

    suspend fun setCassetteEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CASSETTE_ENABLED] = enabled }
    }

    suspend fun setCassetteKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CASSETTE_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setCassetteDim(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CASSETTE_DIM] = enabled }
    }

    suspend fun setCassetteColorSource(source: CassetteColorSource) {
        context.dataStore.edit { it[Keys.CASSETTE_COLOR_SOURCE] = source.name }
    }

    /** ARGB. */
    suspend fun setCassetteCustomColor(argb: Int) {
        context.dataStore.edit { it[Keys.CASSETTE_CUSTOM_COLOR] = argb }
    }

    suspend fun setCassetteShowExitHint(show: Boolean) {
        context.dataStore.edit { it[Keys.CASSETTE_SHOW_EXIT_HINT] = show }
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
        val ILYRA_UNLOCKED       = booleanPreferencesKey("ilyra_unlocked")
        val ILYRA_ENABLED        = booleanPreferencesKey("ilyra_enabled")
        val ILYRA_CLICK_SOUNDS   = booleanPreferencesKey("ilyra_click_sounds")
        val ILYRA_CLICK_VOLUME   = intPreferencesKey("ilyra_click_volume")
        val ILYRA_CLICK_PITCH    = intPreferencesKey("ilyra_click_pitch")
        val ILYRA_BODY_COLOR     = intPreferencesKey("ilyra_body_color")
        val CASSETTE_ENABLED         = booleanPreferencesKey("cassette_enabled")
        val CASSETTE_KEEP_SCREEN_ON  = booleanPreferencesKey("cassette_keep_screen_on")
        val CASSETTE_DIM             = booleanPreferencesKey("cassette_dim")
        val CASSETTE_COLOR_SOURCE    = stringPreferencesKey("cassette_color_source")
        val CASSETTE_CUSTOM_COLOR    = intPreferencesKey("cassette_custom_color")
        val CASSETTE_SHOW_EXIT_HINT  = booleanPreferencesKey("cassette_show_exit_hint")
    }
}
