package com.crsmthw.lyra.util.visualizer

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FftUtilsTest {

    // ── getFftMagnitude ──────────────────────────────────────────────────────

    @Test
    fun `getFftMagnitude returns empty for tiny input`() {
        assertEquals(0, getFftMagnitude(ByteArray(0)).size)
        assertEquals(0, getFftMagnitude(ByteArray(1)).size)
        assertEquals(0, getFftMagnitude(ByteArray(2)).size)
    }

    @Test
    fun `getFftMagnitude computes hypot of real and imaginary pairs`() {
        // fftBytes layout: DC pair (bytes[0..1]) is skipped; then (real, imag) pairs.
        // Magnitude[k] = hypot(fftBytes[(k+1)*2], fftBytes[(k+1)*2+1])
        val bytes = ByteArray(8) // 4 pairs → n = 8/2 - 1 = 3 magnitudes
        bytes[0] = 0; bytes[1] = 0   // DC — ignored
        bytes[2] = 3; bytes[3] = 4   // bin 1 → hypot(3, 4) = 5
        bytes[4] = 6; bytes[5] = 8   // bin 2 → hypot(6, 8) = 10
        bytes[6] = 0; bytes[7] = 5   // bin 3 → hypot(0, 5) = 5

        val mag = getFftMagnitude(bytes)
        assertEquals(3, mag.size)
        assertEquals(5.0, mag[0], 1e-9)
        assertEquals(10.0, mag[1], 1e-9)
        assertEquals(5.0, mag[2], 1e-9)
    }

    // ── hzToFftIndex — pins the 2x/48kHz bin-mapping bug fix ─────────────────

    @Test
    fun `hzToFftIndex at 48 kHz maps 6000 Hz to bin 128`() {
        // 6000 * 1024 / 48000 = 128.0
        assertEquals(128, hzToFftIndex(6000, sampleRateHz = 48000, captureSize = 1024))
    }

    @Test
    fun `hzToFftIndex at 44100 Hz maps 6000 Hz to bin 139`() {
        // 6000 * 1024 / 44100 ≈ 139.3 → truncated to 139
        assertEquals(139, hzToFftIndex(6000, sampleRateHz = 44100, captureSize = 1024))
    }

    @Test
    fun `hzToFftIndex clamps to zero for 0 Hz`() {
        assertEquals(0, hzToFftIndex(0))
    }

    @Test
    fun `hzToFftIndex clamps to last valid index for very high frequency`() {
        // captureSize/2 - 2 = 510 for captureSize 1024
        assertEquals(510, hzToFftIndex(100_000, sampleRateHz = 48000, captureSize = 1024))
    }

    @Test
    fun `hzToFftIndex uses default sample rate when given zero`() {
        // sampleRateHz = 0 → falls back to DefaultSampleRateHz = 48000
        assertEquals(hzToFftIndex(6000, 48000, 1024), hzToFftIndex(6000, 0, 1024))
    }

    @Test
    fun `hzToFftIndex uses default sample rate when given negative`() {
        assertEquals(hzToFftIndex(6000, 48000, 1024), hzToFftIndex(6000, -1, 1024))
    }

    // ── groupRms ─────────────────────────────────────────────────────────────

    @Test
    fun `groupRms returns input unchanged when bands greater than or equal to size`() {
        val data = doubleArrayOf(1.0, 2.0, 3.0)
        assertTrue(data === groupRms(data, 3))
        assertTrue(data === groupRms(data, 5))
    }

    @Test
    fun `groupRms returns input unchanged for zero or negative bands`() {
        val data = doubleArrayOf(1.0, 2.0, 3.0)
        assertTrue(data === groupRms(data, 0))
        assertTrue(data === groupRms(data, -1))
    }

    @Test
    fun `groupRms computes root mean square of each group`() {
        // size=4, bands=2 → group 0 = [0,2), group 1 = [2,4)
        val data = doubleArrayOf(3.0, 4.0, 1.0, 2.0)
        val result = groupRms(data, 2)
        assertEquals(2, result.size)
        // group 0: sqrt((9+16)/2) = sqrt(12.5)
        assertEquals(sqrt(12.5), result[0], 1e-9)
        // group 1: sqrt((1+4)/2) = sqrt(2.5)
        assertEquals(sqrt(2.5), result[1], 1e-9)
    }

    @Test
    fun `groupRms handles uneven split correctly`() {
        // size=5, bands=2 → group 0 indices [0,2), group 1 indices [2,5)
        val data = doubleArrayOf(1.0, 1.0, 2.0, 2.0, 2.0)
        val result = groupRms(data, 2)
        assertEquals(2, result.size)
        // group 0 (2 elements): sqrt((1+1)/2) = 1.0
        assertEquals(1.0, result[0], 1e-9)
        // group 1 (3 elements): sqrt((4+4+4)/3) = 2.0
        assertEquals(2.0, result[1], 1e-9)
    }

    // ── groupMean ────────────────────────────────────────────────────────────

    @Test
    fun `groupMean returns input unchanged when bands greater than or equal to size`() {
        val data = doubleArrayOf(1.0, 2.0, 3.0)
        assertTrue(data === groupMean(data, 3))
        assertTrue(data === groupMean(data, 10))
    }

    @Test
    fun `groupMean returns input unchanged for zero or negative bands`() {
        val data = doubleArrayOf(1.0, 2.0, 3.0)
        assertTrue(data === groupMean(data, 0))
        assertTrue(data === groupMean(data, -1))
    }

    @Test
    fun `groupMean computes arithmetic mean of each group`() {
        val data = doubleArrayOf(2.0, 4.0, 10.0, 20.0)
        val result = groupMean(data, 2)
        assertEquals(2, result.size)
        assertEquals(3.0, result[0], 1e-9)
        assertEquals(15.0, result[1], 1e-9)
    }

    @Test
    fun `groupMean handles uneven split correctly`() {
        // size=5, bands=2 → group 0 indices [0,2), group 1 indices [2,5)
        val data = doubleArrayOf(10.0, 20.0, 3.0, 6.0, 9.0)
        val result = groupMean(data, 2)
        assertEquals(2, result.size)
        assertEquals(15.0, result[0], 1e-9)   // (10+20)/2
        assertEquals(6.0, result[1], 1e-9)    // (3+6+9)/3
    }

    // ── applyMilkdropEqualize ────────────────────────────────────────────────

    @Test
    fun `applyMilkdropEqualize leaves first element at unity`() {
        val fft = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        val result = applyMilkdropEqualize(fft, boost = 3.0)
        // i=0 → frac=0 → 1 + 3*-ln(1-0) = 1 + 3*0 = 1.0 → unchanged
        assertEquals(5.0, result[0], 1e-9)
    }

    @Test
    fun `applyMilkdropEqualize gain is monotonically increasing`() {
        val fft = DoubleArray(10) { 1.0 }
        val result = applyMilkdropEqualize(fft, boost = 3.0)
        for (i in 0 until result.size - 1) {
            assertTrue(result[i + 1] > result[i],
                "Expected result[${ i + 1 }] > result[$i], got ${result[i + 1]} <= ${result[i]}")
        }
    }

    @Test
    fun `applyMilkdropEqualize returns single element unchanged`() {
        val fft = doubleArrayOf(7.0)
        val result = applyMilkdropEqualize(fft)
        assertEquals(7.0, result[0], 1e-9)
    }

    @Test
    fun `applyMilkdropEqualize applies log curve`() {
        // For i=2, n=4: frac = 0.5, gain = 1 + 3*-ln(0.5) ≈ 1 + 3*0.6931 ≈ 3.0794
        val fft = doubleArrayOf(10.0, 10.0, 10.0, 10.0)
        val result = applyMilkdropEqualize(fft, boost = 3.0)
        val expectedGain = 1.0 + 3.0 * -ln(1.0 - 0.5)
        assertEquals(10.0 * expectedGain, result[2], 1e-6)
    }

    // ── GravityModel ─────────────────────────────────────────────────────────

    @Test
    fun `GravityModel starts at zero`() {
        val g = GravityModel()
        assertEquals(0f, g.height)
    }

    @Test
    fun `GravityModel update sets target but does not move height`() {
        val g = GravityModel()
        g.update(100f)
        assertEquals(0f, g.height)
    }

    @Test
    fun `GravityModel rises toward target with attack rate`() {
        val g = GravityModel(attack = 0.35f, release = 0.06f)
        g.update(100f)
        g.tickDecay()
        // height += (100 - 0) * 0.35 = 35
        assertEquals(35f, g.height, 0.01f)
    }

    @Test
    fun `GravityModel decays toward zero with release rate`() {
        val g = GravityModel(attack = 0.35f, release = 0.06f)
        g.update(100f)
        // Rise a few ticks
        repeat(20) { g.tickDecay() }
        val peaked = g.height
        assertTrue(peaked > 50f, "Expected height to have risen significantly")

        // Now set target to 0 and decay
        g.update(0f)
        g.tickDecay()
        assertTrue(g.height < peaked, "Expected height to decrease on decay tick")
    }

    @Test
    fun `GravityModel snaps to zero when attack result is below 0_5`() {
        // attack of 0.35 * target of 1 = 0.35 which is < 0.5 → snaps to 0
        val g = GravityModel(attack = 0.35f, release = 0.06f)
        g.update(1f)
        g.tickDecay()
        assertEquals(0f, g.height)
    }

    @Test
    fun `GravityModel decay path snaps to zero when crossing threshold`() {
        val g = GravityModel(attack = 0.35f, release = 0.06f)
        // Rise to a comfortable height
        g.update(100f)
        repeat(30) { g.tickDecay() }
        assertTrue(g.height > 50f)
        // Now decay toward zero
        g.update(0f)
        var ticks = 0
        while (g.height > 0f && ticks < 500) { g.tickDecay(); ticks++ }
        assertEquals(0f, g.height, "Expected height to snap to 0 during decay")
        assertTrue(ticks > 1, "Expected multiple ticks before snap")
    }

    @Test
    fun `GravityModel tickDecay is no-op when target and height are both zero`() {
        val g = GravityModel()
        g.tickDecay()
        assertEquals(0f, g.height)
    }

    // ── Agc ──────────────────────────────────────────────────────────────────

    @Test
    fun `Agc returns null when peak is at or below gate low`() {
        val agc = Agc(gateLow = 2.5)
        assertNull(agc.process(doubleArrayOf(0.0, 1.0, 2.5)))
        assertNull(agc.process(doubleArrayOf(0.0)))
    }

    @Test
    fun `Agc returns non-null when peak is above gate low`() {
        val agc = Agc()
        assertNotNull(agc.process(doubleArrayOf(0.0, 0.0, 10.0)))
    }

    @Test
    fun `Agc smoothstep gate is 0_5 at midpoint of gate range`() {
        // gateLow=2.5, gateHigh=6.0 → midpoint peak = 4.25
        // t = (4.25 - 2.5) / (6.0 - 2.5) = 0.5
        // gate = 0.5^2 * (3 - 2*0.5) = 0.25 * 2 = 0.5
        val agc = Agc(target = 130.0, minRef = 6.0, attack = 0.6, release = 0.10,
            gateLow = 2.5, gateHigh = 6.0)
        val raw = doubleArrayOf(4.25)
        val result = agc.process(raw)!!
        // ref starts at minRef=6.0 → ref = 6.0 + (4.25-6.0)*0.10 = 5.825 → max(5.825, 6.0) = 6.0
        // Actually: peak(4.25) < ref(6.0), so release path: ref = 6.0 + (4.25-6.0)*0.10 = 5.825
        // But 5.825 < minRef(6.0) → ref = 6.0
        // gain = (130/6) * 0.5
        val expectedGain = (130.0 / 6.0) * 0.5
        assertEquals(4.25 * expectedGain, result[0], 1e-6)
    }

    @Test
    fun `Agc smoothstep gate is fully open above gateHigh`() {
        val agc = Agc(target = 130.0, minRef = 6.0, attack = 0.6,
            gateLow = 2.5, gateHigh = 6.0)
        val raw = doubleArrayOf(10.0)
        val result = agc.process(raw)!!
        // peak=10 > ref=6, attack: ref = 6 + (10-6)*0.6 = 8.4
        // t = (10-2.5)/(6-2.5) = 2.14 → clamped to 1.0
        // gate = 1.0^2 * (3-2) = 1.0
        // gain = 130/8.4 * 1.0
        val expectedGain = 130.0 / 8.4
        assertEquals(10.0 * expectedGain, result[0], 1e-6)
    }

    @Test
    fun `Agc reference tracks peak over consecutive frames`() {
        val agc = Agc(target = 130.0, minRef = 6.0, attack = 0.6, release = 0.10)
        // Frame 1: peak 10 → ref = 6 + (10-6)*0.6 = 8.4
        agc.process(doubleArrayOf(10.0))
        // Frame 2: peak 10 again → ref = 8.4 + (10-8.4)*0.6 = 9.36
        val r2 = agc.process(doubleArrayOf(10.0, 0.0))!!
        val expectedRef = 8.4 + (10.0 - 8.4) * 0.6  // 9.36
        val expectedGain = (130.0 / expectedRef) * 1.0  // gate fully open at peak=10
        assertEquals(10.0 * expectedGain, r2[0], 1e-6)
    }

    // ── getMirrorFft ─────────────────────────────────────────────────────────

    @Test
    fun `getMirrorFft produces reversed then original`() {
        val fft = doubleArrayOf(1.0, 2.0, 3.0)
        val result = getMirrorFft(fft)
        assertEquals(6, result.size)
        assertEquals(3.0, result[0], 1e-9)
        assertEquals(2.0, result[1], 1e-9)
        assertEquals(1.0, result[2], 1e-9)
        assertEquals(1.0, result[3], 1e-9)
        assertEquals(2.0, result[4], 1e-9)
        assertEquals(3.0, result[5], 1e-9)
    }

    // ── getCircleFft ─────────────────────────────────────────────────────────

    @Test
    fun `getCircleFft returns input for fewer than 3 elements`() {
        val fft = doubleArrayOf(1.0, 2.0)
        assertTrue(fft === getCircleFft(fft))
    }

    @Test
    fun `getCircleFft wraps with correct padding pattern`() {
        // [10, 20, 30, 40]:
        //   p = [0,0,0,0,0,0]
        //   forEach: p = [0, 10, 20, 30, 40, 0]
        //   p[0] = fft[lastIndex-1] = fft[2] = 30
        //   p[lastIndex-1] = p[4] = fft[0] = 10  (overwrites the 40)
        //   p[lastIndex] = p[5] = fft[1] = 20
        //   Result: [30, 10, 20, 30, 10, 20]
        val fft = doubleArrayOf(10.0, 20.0, 30.0, 40.0)
        val result = getCircleFft(fft)
        assertEquals(6, result.size)
        assertEquals(30.0, result[0], 1e-9)
        assertEquals(10.0, result[1], 1e-9)
        assertEquals(20.0, result[2], 1e-9)
        assertEquals(30.0, result[3], 1e-9)
        assertEquals(10.0, result[4], 1e-9)
        assertEquals(20.0, result[5], 1e-9)
    }

    // ── applyFrequencyTilt ───────────────────────────────────────────────────

    @Test
    fun `applyFrequencyTilt first element is unchanged`() {
        val fft = doubleArrayOf(5.0, 5.0, 5.0)
        val result = applyFrequencyTilt(fft, tiltFactor = 4.0)
        assertEquals(5.0, result[0], 1e-9)  // i=0 → gain = 1+0 = 1
    }

    @Test
    fun `applyFrequencyTilt last element has max boost`() {
        val fft = doubleArrayOf(5.0, 5.0, 5.0)
        val result = applyFrequencyTilt(fft, tiltFactor = 4.0)
        // i=2, n=3: gain = 1 + 4*(2/2) = 5
        assertEquals(25.0, result[2], 1e-9)
    }

    @Test
    fun `applyFrequencyTilt returns single element unchanged`() {
        val fft = doubleArrayOf(7.0)
        val result = applyFrequencyTilt(fft)
        assertEquals(7.0, result[0], 1e-9)
    }

    // ── getPowerFft ──────────────────────────────────────────────────────────

    @Test
    fun `getPowerFft squares and divides by param`() {
        val fft = doubleArrayOf(10.0, 20.0)
        val result = getPowerFft(fft, param = 100.0)
        assertEquals(1.0, result[0], 1e-9)   // 100/100
        assertEquals(4.0, result[1], 1e-9)   // 400/100
    }

    // ── getFftMagnitudeRange ─────────────────────────────────────────────────

    @Test
    fun `getFftMagnitudeRange extracts the correct frequency band`() {
        // Build fftBytes: bin k has real=k, imag=0, so magnitude[k-1] = k (after DC skip).
        // captureSize=20 → 10 bins, 8 magnitudes after DC drop (indices 0..7).
        val captureSize = 20
        val bytes = ByteArray(captureSize)
        for (k in 1 until captureSize / 2) {
            bytes[k * 2] = k.toByte()
            bytes[k * 2 + 1] = 0
        }
        // At sampleRate=20, captureSize=20: bin spacing = 1 Hz.
        // hzToFftIndex(2, 20, 20) = (2*20/20) = 2; hzToFftIndex(4, 20, 20) = 4.
        // Magnitudes array: [1, 2, 3, 4, 5, 6, 7, 8], copyOfRange(2, 4) = [3.0, 4.0]
        val range = getFftMagnitudeRange(bytes, startHz = 2, endHz = 4, sampleRateHz = 20)
        assertEquals(2, range.size)
        assertEquals(3.0, range[0], 1e-9)
        assertEquals(4.0, range[1], 1e-9)
    }

    // ── toCartesian ──────────────────────────────────────────────────────────

    @Test
    fun `toCartesian at theta zero returns radius along x`() {
        val result = toCartesian(5f, 0f)
        assertEquals(5f, result[0], 0.001f)
        assertEquals(0f, result[1], 0.001f)
    }

    @Test
    fun `toCartesian at 90 degrees returns radius along y`() {
        val theta = (Math.PI / 2).toFloat()
        val result = toCartesian(5f, theta)
        assertEquals(0f, result[0], 0.01f)
        assertEquals(5f, result[1], 0.01f)
    }
}
