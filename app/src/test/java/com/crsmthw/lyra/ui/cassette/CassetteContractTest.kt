package com.crsmthw.lyra.ui.cassette

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CassetteChoreographerTest {

    @Test
    fun `every advance alternates flip then eject and toggles the side`() {
        val c = CassetteChoreographer()
        assertEquals(CassetteSide.A, c.side)
        assertEquals(CassetteMove.FLIP, c.advance());  assertEquals(CassetteSide.B, c.side)
        assertEquals(CassetteMove.EJECT, c.advance()); assertEquals(CassetteSide.A, c.side)
        assertEquals(CassetteMove.FLIP, c.advance());  assertEquals(CassetteSide.B, c.side)
        assertEquals(CassetteMove.EJECT, c.advance()); assertEquals(CassetteSide.A, c.side)
    }

    @Test
    fun `a long run never breaks the alternation`() {
        val c = CassetteChoreographer()
        for (i in 0 until 50) {
            val sideBefore = c.side
            val move = c.advance()
            assertEquals(if (i % 2 == 0) CassetteMove.FLIP else CassetteMove.EJECT, move, "change $i")
            assertEquals(if (sideBefore == CassetteSide.A) CassetteMove.FLIP else CassetteMove.EJECT, move)
            assertTrue(c.side != sideBefore, "change $i must toggle the side")
        }
    }

    @Test
    fun `a shell starting on side B ejects first`() {
        val c = CassetteChoreographer(CassetteSide.B)
        assertEquals(CassetteMove.EJECT, c.advance())
        assertEquals(CassetteSide.A, c.side)
        assertEquals(CassetteMove.FLIP, c.advance())
        assertEquals(CassetteSide.B, c.side)
    }
}

class CassetteFitTest {

    private fun close(a: Float, b: Float, eps: Float = 0.01f) = kotlin.math.abs(a - b) <= eps

    @Test
    fun `fold 8 cover screen is portrait and bleeds to the short edge`() {
        val fit = cassetteFit(475.4f, 751.2f)
        assertTrue(fit.portrait)
        assertTrue(close(fit.short, 475.4f), "short=${fit.short}")
        assertTrue(close(fit.long, 475.4f * CASSETTE_ASPECT), "long=${fit.long}")
        assertTrue(fit.long <= 751.2f + 0.01f)
    }

    @Test
    fun `unfolded landscape spans the full width and letterboxes top and bottom`() {
        val fit = cassetteFit(932.6f, 704f)
        assertTrue(!fit.portrait)
        assertTrue(close(fit.long, 932.6f), "long=${fit.long}")
        assertTrue(close(fit.short, 932.6f / CASSETTE_ASPECT), "short=${fit.short}")
        assertTrue(fit.short < 704f)
    }

    @Test
    fun `unfolded held portrait uses the height as the long edge`() {
        val fit = cassetteFit(704f, 932.6f)
        assertTrue(fit.portrait)
        assertTrue(close(fit.long, 932.6f))
        assertTrue(fit.short <= 704f)
    }

    @Test
    fun `a 21 by 9 phone bleeds on the short edge with bands along the long edges`() {
        val fit = cassetteFit(411f, 960f)
        assertTrue(fit.portrait)
        assertTrue(close(fit.short, 411f))
        assertTrue(fit.long < 960f)
    }

    @Test
    fun `ratio is always the shell ratio and never exceeds the window`() {
        for ((w, h) in listOf(100f to 100f, 320f to 900f, 1600f to 700f, 1f to 1000f, 1000f to 1f)) {
            val fit = cassetteFit(w, h)
            assertTrue(close(fit.long / fit.short, CASSETTE_ASPECT, 0.001f))
            assertTrue(fit.long <= maxOf(w, h) + 0.001f && fit.short <= minOf(w, h) + 0.001f, "$w x $h → $fit")
        }
    }
}

class CassettePaletteTest {

    @Test
    fun `rgb to hsl round trips the primaries`() {
        assertEquals(0f, Color.Red.toHsl().h)
        assertEquals(120f, Color.Green.toHsl().h)
        assertEquals(240f, Color.Blue.toHsl().h)
        assertEquals(0f, Color.White.toHsl().s)
        assertEquals(1f, Color.White.toHsl().l)
        assertEquals(0f, Color.Black.toHsl().l)
    }

    @Test
    fun `palette keeps the seed hue and orders lightness paper over hub over tape over ink`() {
        val seed = Color(0xFF8B6BD1)
        val p = CassettePalette.from(seed)
        val hue = seed.toHsl().h
        for (c in listOf(p.hub, p.hubDeep, p.tape, p.labelPaper, p.labelGrid, p.ink)) {
            val d = kotlin.math.abs(c.toHsl().h - hue)
            assertTrue(minOf(d, 360f - d) < 2f, "hue drift ${c.toHsl().h} vs $hue")
        }
        assertTrue(p.labelPaper.toHsl().l > p.hub.toHsl().l)
        assertTrue(p.hub.toHsl().l > p.tape.toHsl().l)
        assertTrue(p.tape.toHsl().l > p.ink.toHsl().l)
    }

    @Test
    fun `a grey seed still tints and a neon seed is tamed`() {
        val grey = CassettePalette.from(Color(0xFF777777))
        assertTrue(grey.hub.toHsl().s >= 0.35f - 0.01f)
        val neon = CassettePalette.from(Color(0xFF00FF00))
        assertTrue(neon.hub.toHsl().s <= 0.80f + 0.01f)
    }
}
