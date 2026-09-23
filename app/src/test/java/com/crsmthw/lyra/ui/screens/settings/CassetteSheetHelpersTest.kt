package com.crsmthw.lyra.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.crsmthw.lyra.ui.cassette.CASSETTE_DEFAULT_CUSTOM_COLOR
import com.crsmthw.lyra.ui.cassette.CassettePalette
import com.crsmthw.lyra.ui.cassette.toHsl
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CassetteSheetHelpersTest {

    private fun close(a: Float, b: Float, eps: Float) = abs(a - b) <= eps

    /** Equal up to the 8-bit quantisation `Color` stores sRGB at (a hue re-derived from a quantised low-saturation seed can shift a couple of steps). */
    private fun sameColor(a: Color, b: Color): Boolean {
        val step = 3f / 255f
        return close(a.red, b.red, step) && close(a.green, b.green, step) && close(a.blue, b.blue, step)
    }

    /** Hue distance on the circle (0 and 360 are the same hue). */
    private fun hueClose(a: Float, b: Float, eps: Float): Boolean {
        val d = abs(a - b) % 360f
        return minOf(d, 360f - d) <= eps
    }

    @Test
    fun `seed round-trips through ARGB back to the same slider positions`() {
        for (hue in listOf(0f, 37f, 120f, 200f, 271f, 359f)) {
            for (sat in listOf(CASSETTE_SAT_MIN, 0.5f, 0.66f, CASSETTE_SAT_MAX)) {
                val back = cassetteHueSat(cassetteSeedColor(hue, sat).toArgb())
                assertTrue(hueClose(back.hue, hue, 1.5f), "hue $hue → ${back.hue}")
                assertTrue(close(back.saturation, sat, 0.01f), "sat $sat → ${back.saturation}")
            }
        }
    }

    @Test
    fun `seed is stored at the palette's hub lightness`() {
        val hsl = cassetteSeedColor(200f, 0.6f).toHsl()
        assertTrue(close(hsl.l, CASSETTE_SEED_LIGHTNESS, 0.01f), "l=${hsl.l}")
    }

    @Test
    fun `seed accepts the range edges and clamps out-of-range inputs instead of throwing`() {
        // Color.hsl validates its arguments; these must not reach it unclamped.
        cassetteSeedColor(360f, CASSETTE_SAT_MAX)
        cassetteSeedColor(0f, CASSETTE_SAT_MIN)
        val low  = cassetteSeedColor(-10f, 0f).toHsl()
        val high = cassetteSeedColor(400f, 1f).toHsl()
        assertTrue(close(low.s, CASSETTE_SAT_MIN, 0.01f), "low s=${low.s}")
        assertTrue(close(high.s, CASSETTE_SAT_MAX, 0.01f), "high s=${high.s}")
    }

    @Test
    fun `a grey stored colour maps to hue 0 and the minimum saturation`() {
        val hs = cassetteHueSat(Color(0xFF808080).toArgb())
        assertEquals(0f, hs.hue)
        assertEquals(CASSETTE_SAT_MIN, hs.saturation)
    }

    @Test
    fun `presets outside the palette's saturation window come back clamped`() {
        val blueGrey = cassetteHueSat(0xFF90A4AE.toInt())   // s ≈ 0.16
        val orange   = cassetteHueSat(0xFFFB8C00.toInt())   // s = 1.0
        assertEquals(CASSETTE_SAT_MIN, blueGrey.saturation)
        assertEquals(CASSETTE_SAT_MAX, orange.saturation)
        assertTrue(orange.hue in 0f..360f && blueGrey.hue in 0f..360f)
    }

    @Test
    fun `the slider's saturation window is exactly the palette's clamp`() {
        // Outside the window the palette clamps, so the look must not change: no dead zone at either end.
        val atMin    = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MIN, 0.56f))
        val belowMin = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MIN - 0.2f, 0.56f))
        val atMax    = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MAX, 0.56f))
        val aboveMax = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MAX + 0.15f, 0.56f))
        assertTrue(sameColor(atMin.hub, belowMin.hub), "${atMin.hub} vs ${belowMin.hub}")
        assertTrue(sameColor(atMax.hub, aboveMax.hub), "${atMax.hub} vs ${aboveMax.hub}")
        // …and inside it the look does move.
        val inside = CassettePalette.from(Color.hsl(200f, 0.6f, 0.56f))
        assertTrue(!sameColor(inside.hub, atMin.hub) && !sameColor(inside.hub, atMax.hub))
        // A NARROWED palette clamp would put a dead zone just inside each end — the look must move there too.
        val nearMin = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MIN + 0.03f, 0.56f))
        val nearMax = CassettePalette.from(Color.hsl(200f, CASSETTE_SAT_MAX - 0.03f, 0.56f))
        assertTrue(!sameColor(nearMin.hub, atMin.hub), "${nearMin.hub} vs ${atMin.hub}")
        assertTrue(!sameColor(nearMax.hub, atMax.hub), "${nearMax.hub} vs ${atMax.hub}")
    }

    @Test
    fun `ten distinct presets, the ad's purple first`() {
        assertEquals(10, CassetteSwatches.size)
        assertEquals(CASSETTE_DEFAULT_CUSTOM_COLOR, CassetteSwatches.first().argb)
        assertEquals(10, CassetteSwatches.map { it.argb }.toSet().size)
        assertEquals(10, CassetteSwatches.map { it.nameRes }.toSet().size)
    }

    // ── CassetteOwnWrites: the sheet must not re-seed its sliders from its own write's echo ──

    @Test
    fun `an own write's echo is recognised, an outside value is not`() {
        val own = CassetteOwnWrites()
        own.record(1)
        assertTrue(own.isEcho(1))
        assertFalse(own.isEcho(1), "consumed: the same value again is an outside change")
        assertFalse(own.isEcho(2))
    }

    @Test
    fun `two quick writes arriving one by one are both echoes`() {
        val own = CassetteOwnWrites()
        own.record(10); own.record(20)
        assertTrue(own.isEcho(10))
        assertTrue(own.isEcho(20))
    }

    @Test
    fun `a conflated older write is dropped with the newer echo`() {
        val own = CassetteOwnWrites()
        own.record(10); own.record(20)
        assertTrue(own.isEcho(20))
        assertFalse(own.isEcho(10), "10 was superseded; seeing it now is an outside change")
    }

    @Test
    fun `a repeated value arriving in order is recognised every time`() {
        val own = CassetteOwnWrites()
        own.record(10); own.record(20); own.record(10)
        assertTrue(own.isEcho(10))
        assertTrue(own.isEcho(20))
        assertTrue(own.isEcho(10))
    }

    @Test
    fun `an outside change clears what was in flight`() {
        val own = CassetteOwnWrites()
        own.record(10)
        assertFalse(own.isEcho(99))
        assertFalse(own.isEcho(10))
    }

    @Test
    fun `a hue released at the right end is stored as hue 0`() {
        // Why the echo must never re-seed the Hue slider: 360 and 0 are one colour, and toHsl reads 0.
        val argb = cassetteSeedColor(360f, 0.6f).toArgb()
        assertEquals(argb, cassetteSeedColor(0f, 0.6f).toArgb())
        assertTrue(cassetteHueSat(argb).hue < 1f)
    }
}
