package com.crsmthw.lyra.util.visualizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.apache.commons.math3.analysis.interpolation.AkimaSplineInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

class FftWavePainter(
    var startHz : Int   = 0,
    var endHz   : Int   = 2000,
    var numBands: Int   = 128,
    var ampR    : Float = 0.4f,
) {
    private val akima        = AkimaSplineInterpolator()
    private val path         = Path()
    private var models       = Array(0) { GravityModel() }
    private var psf          : PolynomialSplineFunction? = null
    private var lastActiveMs : Long = 0L

    var isActive: Boolean = false
        private set

    fun setFftData(fftBytes: ByteArray) {
        var fft = getFftMagnitudeRange(fftBytes, startHz, endHz)
        if (fft.size < 3 || isQuiet(fft)) return
        lastActiveMs = System.currentTimeMillis()
        fft = applyFrequencyTilt(fft)
        fft = getMirrorFft(fft)
        if (models.size != fft.size) models = Array(fft.size) { GravityModel() }
        models.forEachIndexed { i, m -> m.update(fft[i].toFloat() * ampR) }
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

        canvas.save()
        canvas.translate(0f, height)
        canvas.drawPath(path, paint)
        canvas.restore()
        path.reset()
    }
}
