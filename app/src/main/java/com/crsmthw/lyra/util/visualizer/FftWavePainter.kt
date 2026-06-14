package com.crsmthw.lyra.util.visualizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withTranslation
import org.apache.commons.math3.analysis.interpolation.AkimaSplineInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

class FftWavePainter(
    var startHz : Int   = 0,
    var endHz   : Int   = 6000,   // ProjectM-style: lower half of its 0..Fs/4 spectrum (~0-6 kHz @48k)
    var numBands: Int   = 128,
    var ampR    : Float = 32f,
) {
    private val akima        = AkimaSplineInterpolator()
    private val path         = Path()
    private var models       = Array(0) { GravityModel() }
    private var psf          : PolynomialSplineFunction? = null
    private var lastActiveMs : Long = 0L
    private var binAvg       = DoubleArray(0)   // per-band slow running average (ProjectM normalization)

    /** Frequency-band count the FFT is grouped into — the visualizer resolution (set from settings). */
    var bandCount: Int = 24

    /** true = RMS grouping (dramatic, larger spikes); false = mean (even, smooth). Set from settings. */
    var useRms: Boolean = false

    /** Multiplier on [ampR] from the user's gain-offset setting (1.0 = no change). */
    var gainMul: Float = 1f

    var isActive: Boolean = false
        private set

    fun setFftData(fftBytes: ByteArray) {
        val raw = getFftMagnitudeRange(fftBytes, startHz, endHz)
        if (raw.size < 3) return
        // Group into the chosen resolution (RMS), THEN per-band volume-normalize — ProjectM:
        // divide each band by its OWN slow running average (volume-independent + frequency-
        // balanced; treble dances as hard as bass). Replaces AGC + tilt.
        val grouped = if (useRms) groupRms(raw, bandCount) else groupMean(raw, bandCount)
        if (binAvg.size != grouped.size) binAvg = DoubleArray(grouped.size) { grouped[it] }
        var anyAbove = false
        val norm = DoubleArray(grouped.size) { i ->
            binAvg[i] = binAvg[i] * 0.99 + grouped[i] * 0.01    // slow long-average (ProjectM-like)
            val v = (grouped[i] / binAvg[i].coerceAtLeast(6.0) - 0.5).coerceAtLeast(0.0)
            if (v > 0.0) anyAbove = true
            v
        }
        if (!anyAbove) return
        lastActiveMs = System.currentTimeMillis()
        val fft = getMirrorFft(applyMilkdropEqualize(norm, 1.6))   // ProjectM log treble-lift on top of normalization
        if (models.size != fft.size) models = Array(fft.size) { GravityModel() }
        models.forEachIndexed { i, m -> m.update(fft[i].toFloat() * ampR * gainMul) }
        isActive = true
    }

    fun tickGravity() {
        if (!isActive) return
        if (System.currentTimeMillis() - lastActiveMs > 100L && models.isNotEmpty()) {
            models.forEach { it.update(0f) }
        }
        var anyActive = false
        models.forEach { m ->
            m.tickDecay()
            if (m.height > 0f) anyActive = true
        }
        isActive = anyActive
        if (isActive) rebuildSpline() else psf = null
    }

    private fun rebuildSpline() {
        val n = models.size
        if (n < 5) return
        val x = DoubleArray(n) { (it * numBands).toDouble() / (n - 1) }
        val y = DoubleArray(n) { models[it].height.toDouble() }
        psf = runCatching { akima.interpolate(x, y) }.getOrNull()
    }

    fun draw(canvas: Canvas, paint: Paint, width: Float, height: Float) {
        val psf    = psf ?: return
        val sliceW = width / numBands

        path.moveTo(0f, 1f)
        for (i in 0..numBands) {
            path.lineTo(sliceW * i, -psf.value(i.toDouble()).toFloat().coerceAtLeast(0f))
        }
        path.lineTo(width, 1f)
        path.close()

        canvas.withTranslation(0f, height) {
            drawPath(path, paint)
        }
        path.reset()
    }
}
