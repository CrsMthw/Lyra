package com.crsmthw.lyra.util.visualizer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

fun isQuiet(fft: DoubleArray, threshold: Double = 5.0) = fft.none { it > threshold }

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
