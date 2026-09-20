package com.crsmthw.lyra.ui.ipod.wheel

import android.content.Context

/**
 * The Classic's clicker: a very short tick per wheel detent and a slightly different click for
 * the centre button. Backed by a SoundPool over tiny bundled samples; [enabled] mirrors the
 * iPod's own "Click Sounds" setting (independent of the Lyra haptics toggle).
 *
 * Lifecycle: created once by IPodRoot, [release]d when the iPod leaves composition.
 *
 * STUB — the wheel lane implements this.
 */
class ClickSounds(@Suppress("UNUSED_PARAMETER") context: Context) {
    @Volatile var enabled: Boolean = true

    fun tick() { /* stub */ }
    fun select() { /* stub */ }
    fun release() { /* stub */ }
}
