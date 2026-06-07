package com.crsmthw.lyra.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The Now Playing widget keeps its data in Glance's own per-widget state (the default
 * [androidx.glance.state.PreferencesGlanceStateDefinition]), NOT a side DataStore — that's what lets
 * a write (`updateAppWidgetState`) drive a recomposition. Reading external storage once inside
 * `provideGlance` leaves the content lambda holding a stale value that `updateAll` never refreshes.
 */
internal object WidgetKeys {
    val HAS_TRACK = booleanPreferencesKey("has_track")
    val TITLE     = stringPreferencesKey("title")
    val ARTIST    = stringPreferencesKey("artist")
    val ART_FILE  = stringPreferencesKey("art_file")
    val PLAYING   = booleanPreferencesKey("playing")
    val SHUFFLE   = booleanPreferencesKey("shuffle")
    val REPEAT    = stringPreferencesKey("repeat")
    val ACCENT    = intPreferencesKey("accent")
    val DOMINANT  = intPreferencesKey("dominant")
    val AMOLED    = booleanPreferencesKey("amoled")
}

/** Plain holder used by the composables; built from the Glance [Preferences] state. */
internal data class WidgetSnapshot(
    val hasTrack    : Boolean = false,
    val title       : String  = "",
    val artist      : String  = "",
    val artFile     : String? = null,
    val isPlaying   : Boolean = false,
    val shuffle     : Boolean = false,
    val repeat      : String  = "off",
    val accentArgb  : Int     = 0,
    val dominantArgb: Int     = 0,
    val amoled      : Boolean = false,
)

internal fun Preferences.toWidgetSnapshot() = WidgetSnapshot(
    hasTrack     = this[WidgetKeys.HAS_TRACK] ?: false,
    title        = this[WidgetKeys.TITLE] ?: "",
    artist       = this[WidgetKeys.ARTIST] ?: "",
    artFile      = this[WidgetKeys.ART_FILE],
    isPlaying    = this[WidgetKeys.PLAYING] ?: false,
    shuffle      = this[WidgetKeys.SHUFFLE] ?: false,
    repeat       = this[WidgetKeys.REPEAT] ?: "off",
    accentArgb   = this[WidgetKeys.ACCENT] ?: 0,
    dominantArgb = this[WidgetKeys.DOMINANT] ?: 0,
    amoled       = this[WidgetKeys.AMOLED] ?: false,
)
