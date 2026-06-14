package com.crsmthw.lyra.util.visualizer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

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

// mag[k] is FFT bin (k+1), i.e. frequency (k+1) * sampleRate / 1024. So the index for a
// frequency is hz * 1024 / sampleRate. The old constant (88200) was both 2x too large AND
// assumed 44.1 kHz; this device's output mix runs at 48 kHz (measured). TODO: plumb the
// real samplingRate from the Visualizer callback instead of hardcoding.
fun hzToFftIndex(hz: Int): Int = (hz * 1024 / 48000).coerceIn(0, 255)

fun getFftMagnitudeRange(fftBytes: ByteArray, startHz: Int, endHz: Int): DoubleArray {
    val mag = getFftMagnitude(fftBytes)
    if (mag.isEmpty()) return DoubleArray(0)
    val s = hzToFftIndex(startHz).coerceIn(0, mag.size - 1)
    val e = hzToFftIndex(endHz).coerceIn(s + 1, mag.size)
    return mag.copyOfRange(s, e)
}

/**
 * Down-samples [data] into [bands] groups by **RMS** (root-mean-square of each contiguous bin
 * group), the user-chosen visualizer resolution. RMS (not a plain mean) weights the louder bins
 * so a beat inside a group stays punchy instead of being averaged into mush; it also pools flat
 * "deadzone" bins with livelier neighbours. Returns [data] unchanged when [bands] >= its size.
 */
fun groupRms(data: DoubleArray, bands: Int): DoubleArray {
    if (bands <= 0 || bands >= data.size) return data
    return DoubleArray(bands) { b ->
        val start = b * data.size / bands
        val end   = ((b + 1) * data.size / bands).coerceAtMost(data.size)
        var sumSq = 0.0
        for (i in start until end) sumSq += data[i] * data[i]
        sqrt(sumSq / (end - start).coerceAtLeast(1))
    }
}

/**
 * Down-samples [data] into [bands] groups by plain **mean** — ProjectM's approach (it sums each
 * band; after the per-band normalization a sum and a mean are equivalent). Smoother/calmer than
 * [groupRms], which weights the louder bins for more punch. Returns [data] unchanged when
 * [bands] >= its size.
 */
fun groupMean(data: DoubleArray, bands: Int): DoubleArray {
    if (bands <= 0 || bands >= data.size) return data
    return DoubleArray(bands) { b ->
        val start = b * data.size / bands
        val end   = ((b + 1) * data.size / bands).coerceAtMost(data.size)
        var sum = 0.0
        for (i in start until end) sum += data[i]
        sum / (end - start).coerceAtLeast(1)
    }
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

/**
 * ProjectM / Milkdrop-style logarithmic "equalize" curve. Where [applyFrequencyTilt] ramps
 * gain *linearly* across the band (so it over-boosts the mids), this mirrors MilkdropFFT's
 * `equalize[i] = scaling * log((N/2 - i) / (N/2))`: unity at the low end, leaving the mids
 * largely alone, then rising **log-steeply toward the very top** so the highest frequencies
 * (the percussion "sparkle") counter music's natural bass-heavy falloff without drowning the
 * vocal range. [boost] scales how aggressive the treble lift is.
 */
fun applyMilkdropEqualize(fft: DoubleArray, boost: Double = 3.0): DoubleArray {
    val n = fft.size
    if (n <= 1) return fft
    return DoubleArray(n) { i ->
        val frac = (i.toDouble() / n).coerceAtMost(0.999)   // 0 .. <1, never log(0)
        fft[i] * (1.0 + boost * -ln(1.0 - frac))
    }
}

fun toCartesian(radius: Float, theta: Float) =
    floatArrayOf(radius * cos(theta), radius * sin(theta))
