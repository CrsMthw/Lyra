package com.crsmthw.lyra.util.visualizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.apache.commons.math3.analysis.interpolation.AkimaSplineInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction
import kotlin.math.PI
import kotlin.math.min

class FftCWavePainter(
    var startHz : Int   = 0,
    var endHz   : Int   = 2000,
    var numBands: Int   = 128,
    var radiusR : Float = 1.0f,
    var ampR    : Float = 0.7f,
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
        fft = getPowerFft(fft)
        fft = applyFrequencyTilt(fft)
        fft = getCircleFft(fft)
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
        val x = DoubleArray(n) { ((it - 1) * numBands).toDouble() / (n - 1 - 2) }
        val y = DoubleArray(n) { models[it].height.toDouble() }
        psf = runCatching { akima.interpolate(x, y) }.getOrNull()
    }

    fun draw(canvas: Canvas, paint: Paint, width: Float, height: Float, enabled: Boolean = true) {
        val radius = min(width, height) / 2f * radiusR
        val angle  = (2.0 * PI / numBands).toFloat()

        canvas.save()
        canvas.translate(width / 2f, height / 2f)

        val currentPsf = psf
        if (currentPsf != null) {
            for (i in 0..numBands) {
                val v  = currentPsf.value(i.toDouble()).toFloat().coerceAtLeast(0f)
                val pt = toCartesian(radius + v, angle * i)
                if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
            }
            path.close()
            canvas.drawPath(path, paint)
            path.reset()
        } else if (enabled) {
            canvas.drawCircle(0f, 0f, radius, paint)
        }

        canvas.restore()
    }
}
