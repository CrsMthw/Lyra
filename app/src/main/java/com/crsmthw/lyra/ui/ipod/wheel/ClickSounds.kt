package com.crsmthw.lyra.ui.ipod.wheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.crsmthw.lyra.R

/**
 * The Classic's clicker: a very short tick per wheel detent and a slightly different click for
 * the centre button. Backed by a SoundPool over tiny bundled samples; [enabled] mirrors the
 * iPod's own "Click Sounds" setting (independent of the Lyra haptics toggle).
 *
 * Lifecycle: created once by IPodRoot, [release]d when the iPod leaves composition.
 *
 * Thread-safe: [tick] and [select] can be called from a gesture-thread callback.
 */
class ClickSounds(context: Context) {
    @Volatile var enabled: Boolean = true

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /** ID returned by SoundPool.load, or 0 while still loading. */
    @Volatile private var tickId: Int = 0
    @Volatile private var selectId: Int = 0
    @Volatile private var released: Boolean = false

    // The ids returned by load() before the completion callback fires.
    private var pendingTickId: Int = 0
    private var pendingSelectId: Int = 0

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            if (sampleId == pendingTickId) tickId = sampleId
            if (sampleId == pendingSelectId) selectId = sampleId
        }
        pendingTickId = pool.load(context, R.raw.ipod_click, 1)
        pendingSelectId = pool.load(context, R.raw.ipod_select, 1)
    }

    /** Play the detent tick. Silent if [enabled] is false or the sample is not yet loaded. */
    fun tick() {
        if (!enabled || released) return
        val id = tickId
        if (id != 0) pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** Play the centre-button click. Silent if [enabled] is false or the sample is not yet loaded. */
    fun select() {
        if (!enabled || released) return
        val id = selectId
        if (id != 0) pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    /**
     * Apply the iPod's clicker settings: enabled, volume (0..100) and pitch (a playback rate).
     * STUB for volume/pitch — the wheel lane implements; `enabled` is honoured already.
     */
    fun configure(config: ClickSoundsConfig) {
        enabled = config.enabled
    }

    /** Release the pool. Further calls to [tick]/[select] are silent no-ops. */
    fun release() {
        if (released) return
        released = true
        pool.release()
    }
}
