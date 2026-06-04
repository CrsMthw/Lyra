package com.crsmthw.lyra.util.visualizer

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class GravityModel(
    var height: Float = 0f,
    var dy    : Float = 0f,
    val ay    : Float = 2f,
) {
    fun update(h: Float) {
        if (h > height) {
            height = h
            dy = 0f
        }
        height -= dy
        dy += ay
        if (height < 0f) { height = 0f; dy = 0f }
    }

    fun tickDecay() {
        if (height <= 0f) return
        height -= dy
        dy += ay
        if (height < 0f) { height = 0f; dy = 0f }
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

fun toCartesian(radius: Float, theta: Float) =
    floatArrayOf(radius * cos(theta), radius * sin(theta))
