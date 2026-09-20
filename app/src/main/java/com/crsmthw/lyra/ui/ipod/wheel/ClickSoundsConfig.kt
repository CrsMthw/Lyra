package com.crsmthw.lyra.ui.ipod.wheel

import androidx.compose.runtime.Immutable

/** Pitch of the clicker — a SoundPool playback rate over the bundled samples (0.5..2.0 allowed). */
enum class ClickPitch(val rate: Float) { LOW(0.75f), MEDIUM(1.0f), HIGH(1.3f) }

/**
 * Everything the iPod's Settings menu can say about the clicker. Mirrored from DataStore
 * (`ipod_click_sounds` / `ipod_click_volume` / `ipod_click_pitch`) into [IPodUiState.clickSounds]
 * and pushed into [ClickSounds.configure] by IPodRoot the moment it changes.
 */
@Immutable
data class ClickSoundsConfig(
    val enabled: Boolean = true,
    /** 0..100. Half by default — the first build's clicker was too loud on the Fold. */
    val volumePercent: Int = 50,
    val pitch: ClickPitch = ClickPitch.MEDIUM,
)
