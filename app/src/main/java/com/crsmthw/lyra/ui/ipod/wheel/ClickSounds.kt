package com.crsmthw.lyra.ui.ipod.wheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.crsmthw.lyra.R
import kotlin.math.pow

/**
 * The Classic's clicker: a very short tick per wheel detent and a slightly different click for
 * the centre button. Backed by a SoundPool over tiny bundled samples; [enabled] mirrors the
 * iPod's own "Click Sounds" setting (independent of the Lyra haptics toggle).
 *
 * Lifecycle: created once by IPodRoot, [release]d when the iPod leaves composition.
 *
 * Thread-safe: [tick] and [select] can be called from a gesture-thread callback; every field
 * they read is @Volatile.
 *
 * ### Volume curve
 *
 * The SoundPool volume is `(percent / 100f).pow(1.5f)` — a perceptual power curve where
 * 50% gives ~35% linear (−9 dB), so the default sounds noticeably softer than full.
 * Combined with the −10 dBFS samples the default output level is approximately −19 dBFS,
 * versus the round-A level of −3 dBFS.  At 100% the ceiling is −10 dBFS (7 dB below round A).
 * The volumePercent default in [ClickSoundsConfig] can be tuned if this is too quiet over music.
 */
class ClickSounds(context: Context) {
    @Volatile var enabled: Boolean = true
        private set

    /** Effective SoundPool volume (0..1) computed from the perceptual curve. */
    @Volatile private var volume: Float = perceptualVolume(ClickSoundsConfig().volumePercent)

    /** SoundPool playback rate, controlling pitch. */
    @Volatile private var rate: Float = ClickSoundsConfig().pitch.rate

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
        val vol = volume
        val r = rate
        if (id != 0) pool.play(id, vol, vol, 1, 0, r)
    }

    /** Play the centre-button click. Silent if [enabled] is false or the sample is not yet loaded. */
    fun select() {
        if (!enabled || released) return
        val id = selectId
        val vol = volume
        val r = rate
        if (id != 0) pool.play(id, vol, vol, 1, 0, r)
    }

    /**
     * Apply the iPod's clicker settings: enabled, volume and pitch. All three fields are
     * @Volatile so the gesture thread sees the new values on its next tick without any lock.
     */
    fun configure(config: ClickSoundsConfig) {
        enabled = config.enabled
        volume = perceptualVolume(config.volumePercent)
        rate = config.pitch.rate
    }

    /** Release the pool. Further calls to [tick]/[select] are silent no-ops. */
    fun release() {
        if (released) return
        released = true
        pool.release()
    }

    companion object {
        /**
         * Perceptual volume curve: `(percent / 100f)^1.5`.  A power of 1.5 sits between linear
         * and square-law; at 50% it produces ~0.354 (−9.0 dB), making the default clearly quieter
         * than full without disappearing.  At 0% it's silent; at 100% it's 1.0.
         */
        private fun perceptualVolume(percent: Int): Float =
            (percent.coerceIn(0, 100) / 100f).pow(1.5f)
    }
}
