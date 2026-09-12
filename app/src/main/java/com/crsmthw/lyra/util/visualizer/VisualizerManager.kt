package com.crsmthw.lyra.util.visualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One captured FFT frame, carried together with the **output-mix sample rate that produced it** so
 * the bin→Hz mapping in [hzToFftIndex] is exact instead of assuming 48 kHz. Bundling the rate with
 * the frame (rather than publishing it as a second flow) keeps the two atomically consistent and —
 * because every consumer already collects [VisualizerManager.fftData] — costs no extra plumbing in
 * `LyraNavGraph` or the CompositionLocals.
 *
 * Deliberately **not** a data class: [bytes] is an array, so a generated `equals` would compare it
 * by identity anyway. Identity is exactly what is wanted here — `StateFlow` conflates by `equals`
 * and every capture is a fresh `copyOf`, so every frame stays a distinct emission.
 */
class FftFrame(
    val bytes        : ByteArray,
    val sampleRateHz : Int,
)

class VisualizerManager(private val context: Context) {
    private var visualizer: Visualizer? = null

    private val _fftData = MutableStateFlow<FftFrame?>(null)
    val fftData: StateFlow<FftFrame?> = _fftData

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable

    // A `Visualizer` binds to the audio output route that was active when it was created
    // and goes silent if the route later changes (e.g. speaker → Bluetooth headphones).
    // We watch for output-device changes and re-create it so it re-binds to the current
    // route. `started` tracks whether capture should be running; `routeDirty` remembers a
    // route change that happened while paused so the next start() recreates fresh.
    private var started     = false
    private var routeDirty  = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler  = Handler(Looper.getMainLooper())
    private val rebindRunnable = Runnable {
        if (!started) return@Runnable
        recreate()
        routeDirty = false
        runCatching { visualizer?.enabled = true }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?)    = onRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onRouteChanged()
    }

    init {
        // Fires once on registration with the current devices too; harmless because the
        // guard in onRouteChanged ignores it while no Visualizer exists / capture is idle.
        audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    fun tryInitialize() {
        if (visualizer != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            // Disable the Visualizer's built-in auto-gain. The default
            // SCALING_MODE_NORMALIZED rescales every frame so the loudest bin sits
            // at full scale, which means even the noise floor of a quiet/silent
            // track gets amplified into constant motion. AS_PLAYED reflects the
            // actual output level; the Agc in the painters re-normalizes for display.
            // Must be set while the Visualizer is disabled — it is, since it's
            // created disabled and we enable in start().
            runCatching { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED }
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                        _fftData.value = FftFrame(fft.copyOf(), toSampleRateHz(samplingRate))
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true,
            )
            visualizer = v
            _isAvailable.value = true
        } catch (_: Exception) {
            _isAvailable.value = false
        }
    }

    fun start() {
        started = true
        // Lazily create the instance: an earlier tryInitialize() may have bailed on a missing
        // RECORD_AUDIO grant, and the permission can arrive later (Settings' master toggle now asks
        // for it too). tryInitialize() is a no-op while an instance exists or the grant is absent.
        if (visualizer == null) tryInitialize()
        if (routeDirty) { recreate(); routeDirty = false }
        runCatching { visualizer?.enabled = true }
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacks(rebindRunnable)
        runCatching { visualizer?.enabled = false }
        _fftData.value = null
    }

    fun release() {
        mainHandler.removeCallbacks(rebindRunnable)
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        started = false
        _isAvailable.value = false
        _fftData.value = null
    }

    private fun onRouteChanged() {
        // Ignore the change until a Visualizer actually exists for an active capture —
        // before that, the next tryInitialize() creates fresh on the current route anyway.
        if (visualizer == null && !started) return
        routeDirty = true
        if (started) {
            // Debounce the remove+add pair a route switch emits, and let the new route
            // settle before we re-tap it.
            mainHandler.removeCallbacks(rebindRunnable)
            mainHandler.postDelayed(rebindRunnable, 400L)
        }
    }

    private fun recreate() {
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        tryInitialize()
    }

    /**
     * Normalizes the `samplingRate` the capture listener hands us into plain Hz.
     *
     * `Visualizer.OnDataCaptureListener` documents it in **milliHertz** (48 kHz → 48_000_000),
     * which is what `Visualizer.getSamplingRate()` returns and what devices do in practice. Since
     * we cannot audit every OEM audio HAL, accept a plain-Hz value too and reject anything outside
     * a plausible PCM range rather than mis-mapping every bin off a nonsense rate. An implausible
     * value falls back to [DefaultSampleRateHz], i.e. exactly the old hardcoded behaviour.
     */
    private fun toSampleRateHz(reported: Int): Int {
        val hz = if (reported >= MinSampleRateHz * 1000) reported / 1000 else reported
        return if (hz in MinSampleRateHz..MaxSampleRateHz) hz else DefaultSampleRateHz
    }

    private companion object {
        const val MinSampleRateHz =   8_000   // narrowband telephony — the lowest anything mixes at
        const val MaxSampleRateHz = 384_000   // high-res USB DACs
    }
}
