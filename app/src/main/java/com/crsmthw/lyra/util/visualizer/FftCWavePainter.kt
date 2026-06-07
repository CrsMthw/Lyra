package com.crsmthw.lyra.util.visualizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withTranslation
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

    // Beat-snap rotation: shifts which circle position receives the bass bulge
    private var bandOffset    : Int   = 0
    private var rollingEnergy : Float = 0f
    private var lastBeatMs    : Long  = 0L

    var isActive: Boolean = false
        private set

    fun setFftData(fftBytes: ByteArray) {
        var fft = getFftMagnitudeRange(fftBytes, startHz, endHz)
        if (fft.size < 3 || isQuiet(fft)) return
        lastActiveMs = System.currentTimeMillis()

        // Beat detection on raw magnitudes
        val energy = (fft.fold(0.0) { acc, v -> acc + v * v } / fft.size).toFloat()
        rollingEnergy = rollingEnergy * 0.92f + energy * 0.08f
        val now = System.currentTimeMillis()
        if (energy > rollingEnergy * 1.6f && now - lastBeatMs > 300L) {
            // Jump by a varying amount so consecutive beats land on distinct positions
            val jump = kotlin.random.Random.nextInt(fft.size / 4, (fft.size * 2 / 3).coerceAtLeast(fft.size / 4 + 1))
            bandOffset = (bandOffset + jump) % fft.size
            lastBeatMs = now
        }

        fft = getPowerFft(fft)
        fft = applyFrequencyTilt(fft)
        // Rotate so the bass bulge blooms at a different circle position each beat
        if (bandOffset > 0) fft = rotateBands(fft, bandOffset)
        fft = getCircleFft(fft)
        if (models.size != fft.size) models = Array(fft.size) { GravityModel() }
        models.forEachIndexed { i, m -> m.update(fft[i].toFloat() * ampR) }
        isActive = true
    }

    private fun rotateBands(fft: DoubleArray, by: Int): DoubleArray {
        val n     = fft.size
        val shift = ((by % n) + n) % n
        return DoubleArray(n) { i -> fft[(i + shift) % n] }
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

        canvas.withTranslation(width / 2f, height / 2f) {
            val currentPsf = psf
            if (currentPsf != null) {
                for (i in 0..numBands) {
                    val v  = currentPsf.value(i.toDouble()).toFloat().coerceAtLeast(0f)
                    val pt = toCartesian(radius + v, angle * i)
                    if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
                }
                path.close()
                drawPath(path, paint)
                path.reset()
            } else if (enabled) {
                drawCircle(0f, 0f, radius, paint)
            }
        }
    }
}
