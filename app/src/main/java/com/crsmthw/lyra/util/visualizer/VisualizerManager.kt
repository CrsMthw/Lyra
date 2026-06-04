package com.crsmthw.lyra.util.visualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import com.crsmthw.lyra.data.player.PlayerStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VisualizerManager(
    private val context       : Context,
    playerStateManager        : PlayerStateManager,
) {
    private var visualizer: Visualizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _fftData     = MutableStateFlow<ByteArray?>(null)
    val fftData: StateFlow<ByteArray?> = _fftData

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable

    init {
        tryInitialize()
        // Gate capture on playback state to avoid showing the privacy indicator when idle.
        scope.launch {
            playerStateManager.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    if (_isAvailable.value) {
                        if (playing) start() else stop()
                    }
                }
        }
    }

    fun tryInitialize() {
        if (visualizer != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                        _fftData.value = fft.copyOf()
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

    fun start()  { runCatching { visualizer?.enabled = true  } }
    fun stop()   { runCatching { visualizer?.enabled = false }; _fftData.value = null }

    fun release() {
        scope.cancel()
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null
        _isAvailable.value = false
        _fftData.value = null
    }
}
