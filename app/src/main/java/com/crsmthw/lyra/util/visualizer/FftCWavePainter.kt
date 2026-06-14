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
    var endHz   : Int   = 6000,   // ProjectM-style: lower half of its 0..Fs/4 spectrum (~0-6 kHz @48k)
    var numBands: Int   = 128,
    var radiusR : Float = 1.0f,
    var ampR    : Float = 85f,
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

    // Beat-snap rotation: shifts which circle position receives the bass bulge
    private var bandOffset    : Int   = 0
    private var rollingEnergy : Float = 0f
    private var lastBeatMs    : Long  = 0L

    var isActive: Boolean = false
        private set

    fun setFftData(fftBytes: ByteArray) {
        val raw = getFftMagnitudeRange(fftBytes, startHz, endHz)
        if (raw.size < 3) return

        // Beat-matched rotation: detect the kick from the SUB-BASS only (bins 0-3 ≈ 47-187 Hz)
        // — measuring the full band would fire on vocals/instruments, not the beat. On each
        // kick, jump which angular position the bands map to (rotateBands, applied below).
        val bassEnergy = (0..3).sumOf { val v = raw.getOrElse(it) { 0.0 }; v * v }.toFloat()
        rollingEnergy = rollingEnergy * 0.92f + bassEnergy * 0.08f
        val now = System.currentTimeMillis()
        if (bassEnergy > rollingEnergy * 1.5f && now - lastBeatMs > 250L && bandCount > 1) {
            val from = (bandCount / 4).coerceAtLeast(1)
            val jump = kotlin.random.Random.nextInt(from, (bandCount * 2 / 3).coerceAtLeast(from + 1))
            bandOffset = (bandOffset + jump) % bandCount
            lastBeatMs = now
        }

        // Group into the chosen resolution (RMS), THEN per-band volume-normalize — ProjectM's
        // core trick: divide each band by its OWN slow running average so every frequency is
        // judged against its own history. Volume-independent AND frequency-balanced (treble
        // dances as hard as bass), so no single peak dominates.
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

        // ProjectM log "equalize" on top of the normalization — lifts treble (the top-left
        // quadrant of the ring) so it dances as tall as the bass instead of sitting low.
        // Equalize while indices still map to frequency, THEN rotate to scatter the bands.
        var eq = applyMilkdropEqualize(norm, 1.6)
        if (bandOffset > 0) eq = rotateBands(eq, bandOffset)
        val fft = getCircleFft(eq)
        if (models.size != fft.size) models = Array(fft.size) { GravityModel() }
        models.forEachIndexed { i, m -> m.update(fft[i].toFloat() * ampR * gainMul) }
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
