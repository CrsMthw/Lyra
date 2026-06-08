package com.crsmthw.lyra.util.visualizer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Automatic gain control + silence gate for the visualizer (à la ProjectM/Milkdrop).
 *
 * The `Visualizer` runs in `SCALING_MODE_AS_PLAYED`, so the raw magnitudes track the
 * actual output level — quiet at 100% volume, louder at 150%, ~zero at silence. Used
 * directly that makes the visualizer volume-dependent (dead when you turn it down).
 * This normalizes each frame against a slowly-released rolling peak so it looks equally
 * lively at any volume, while a soft gate keyed on the *true* level keeps genuine
 * silence flat (the gate runs on raw AS_PLAYED magnitudes, so AGC can't amplify the
 * noise floor back into motion).
 *
 * One instance per painter (independent state). All constants are tunable on-device.
 */
class Agc(
    private val target  : Double = 130.0, // normalized peak each frame is scaled toward — the brightness knob
    private val minRef  : Double = 6.0,   // floor on the rolling reference; stops soft-but-present frames exploding
    private val attack  : Double = 0.6,   // how fast the reference rises to a louder peak (per FFT frame)
    private val release : Double = 0.10,  // how fast it falls when things quiet down (~1s at the 10 Hz FFT rate)
    private val gateLow : Double = 2.5,   // true level at/below which the frame is treated as silence (gain 0); silence measured at 0.0
    private val gateHigh: Double = 6.0,   // true level at/above which the soft gate is fully open (quietest real music measured ~6)
) {
    private var ref = minRef

    /**
     * Returns the volume-normalized magnitudes for [raw], or `null` if the frame is
     * below the noise floor (caller should skip the update and let the bars decay).
     */
    fun process(raw: DoubleArray): DoubleArray? {
        val peak = raw.maxOrNull() ?: 0.0
        if (peak <= gateLow) return null
        ref = if (peak > ref) ref + (peak - ref) * attack
              else            ref + (peak - ref) * release
        if (ref < minRef) ref = minRef
        // Smoothstep soft gate over [gateLow, gateHigh] so onsets/decays fade instead of snapping.
        val t    = ((peak - gateLow) / (gateHigh - gateLow)).coerceIn(0.0, 1.0)
        val gate = t * t * (3.0 - 2.0 * t)
        val g    = (target / ref) * gate
        return DoubleArray(raw.size) { raw[it] * g }
    }
}

class GravityModel(
    val attack  : Float = 0.35f,
    val release : Float = 0.06f,
) {
    var height: Float = 0f
        private set
    private var target: Float = 0f

    fun update(h: Float) {
        target = h
    }

    fun tickDecay() {
        if (target == 0f && height == 0f) return
        height += (target - height) * if (target > height) attack else release
        if (height < 0.5f) height = 0f
    }
}

fun getFftMagnitude(fftBytes: ByteArray): DoubleArray {
    val n = fftBytes.size / 2 - 1
    if (n <= 0) return DoubleArray(0)
    return DoubleArray(n) { k ->
        val i = (k + 1) * 2
        hypot(fftBytes[i].toDouble(), fftBytes[i + 1].toDouble())
    }
}

fun hzToFftIndex(hz: Int): Int = (hz * 1024 / 88200).coerceIn(0, 255)

fun getFftMagnitudeRange(fftBytes: ByteArray, startHz: Int, endHz: Int): DoubleArray {
    val mag = getFftMagnitude(fftBytes)
    if (mag.isEmpty()) return DoubleArray(0)
    val s = hzToFftIndex(startHz).coerceIn(0, mag.size - 1)
    val e = hzToFftIndex(endHz).coerceIn(s + 1, mag.size)
    return mag.copyOfRange(s, e)
}

fun getMirrorFft(fft: DoubleArray): DoubleArray = fft.reversedArray() + fft

fun getCircleFft(fft: DoubleArray): DoubleArray {
    if (fft.size < 3) return fft
    val p = DoubleArray(fft.size + 2)
    fft.forEachIndexed { i, d -> p[i + 1] = d }
    p[0] = fft[fft.lastIndex - 1]
    p[p.lastIndex - 1] = fft[0]
    p[p.lastIndex] = fft[1]
    return p
}

fun getPowerFft(fft: DoubleArray, param: Double = 100.0) =
    fft.map { it * it / param }.toDoubleArray()

fun applyFrequencyTilt(fft: DoubleArray, tiltFactor: Double = 4.0): DoubleArray {
    val n = fft.size
    if (n <= 1) return fft
    return DoubleArray(n) { i -> fft[i] * (1.0 + tiltFactor * i / (n - 1)) }
}

fun toCartesian(radius: Float, theta: Float) =
    floatArrayOf(radius * cos(theta), radius * sin(theta))
