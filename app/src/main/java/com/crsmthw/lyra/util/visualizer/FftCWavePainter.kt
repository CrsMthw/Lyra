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
    var radiusR : Float = 0.4f,
    var ampR    : Float = 0.6f,
) {
    private val akima  = AkimaSplineInterpolator()
    private val path   = Path()
    private var models = Array(0) { GravityModel() }
    private var psf    : PolynomialSplineFunction? = null

    var isActive: Boolean = false
        private set

    fun setFftData(fftBytes: ByteArray) {
        var fft = getFftMagnitudeRange(fftBytes, startHz, endHz)
        if (fft.size < 3) return
        fft = getPowerFft(fft)
        fft = getCircleFft(fft)
        if (models.size != fft.size) models = Array(fft.size) { GravityModel() }
        models.forEachIndexed { i, m -> m.update(fft[i].toFloat() * ampR) }
        rebuildSpline()
        isActive = true
    }

    fun tickGravity() {
        if (!isActive) return
        var anyActive = false
        models.forEach { m ->
            m.tickDecay()
            if (m.height > 0f) anyActive = true
        }
        isActive = anyActive
        if (isActive) rebuildSpline()
    }

    private fun rebuildSpline() {
        val n = models.size
        if (n < 5) return
        val x = DoubleArray(n) { ((it - 1) * numBands).toDouble() / (n - 1 - 2) }
        val y = DoubleArray(n) { models[it].height.toDouble() }
        psf = runCatching { akima.interpolate(x, y) }.getOrNull()
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val psf      = psf ?: return
        val shortest = min(canvas.width, canvas.height).toFloat()
        val radius   = shortest / 2f * radiusR
        val angle    = (2.0 * PI / numBands).toFloat()

        canvas.save()
        canvas.translate(canvas.width / 2f, canvas.height / 2f)

        // Outer ring
        for (i in 0..numBands) {
            val v  = psf.value(i.toDouble()).toFloat().coerceAtLeast(0f)
            val pt = toCartesian(radius + v, angle * i)
            if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
        }
        path.close()

        // Inner ring (donut cutout)
        for (i in 0..numBands) {
            val v  = psf.value(i.toDouble()).toFloat().coerceAtLeast(0f)
            val pt = toCartesian((radius - v).coerceAtLeast(0f), angle * i)
            if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
        }
        path.close()

        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, paint)
        path.reset()

        canvas.restore()
    }
}
