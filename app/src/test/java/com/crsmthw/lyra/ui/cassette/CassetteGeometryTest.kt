package com.crsmthw.lyra.ui.cassette

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun close(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps

class CassetteReelGeometryTest {
    private val g = CassetteGeometry

    @Test
    fun `pack radii run supply full to take-up full and never touch`() {
        val p0 = packRadii(0f)
        assertEquals(g.RMax, p0.supply); assertEquals(g.RMin, p0.takeUp)
        val p1 = packRadii(1f)
        assertEquals(g.RMin, p1.supply); assertEquals(g.RMax, p1.takeUp)
        val mid = packRadii(0.35f)
        assertTrue(close(mid.supply, 179.35f), "${mid.supply}")
        assertTrue(close(mid.takeUp, 143.65f), "${mid.takeUp}")
        for (i in 0..20) {
            val r = packRadii(i / 20f)
            assertTrue(close(r.supply + r.takeUp, g.RMin + g.RMax))
            assertTrue(r.supply + r.takeUp < g.HubPitch, "packs touch at ${i / 20f}")
        }
    }

    @Test
    fun `progress is clamped and NaN reads as the start`() {
        assertEquals(packRadii(0f), packRadii(-3f))
        assertEquals(packRadii(1f), packRadii(7f))
        assertEquals(packRadii(0f), packRadii(Float.NaN))
    }

    @Test
    fun `the pack edge sweeps through the gap between the hubs over a song`() {
        // the supply pack's inner edge (towards the centre) at p = 0 and p = 1
        val start = g.HubLeftX + packRadii(0f).supply
        val end = g.HubLeftX + packRadii(1f).supply
        assertTrue(start - end > 100f, "sweep ${start - end}")
        assertTrue(g.RMin > g.HubRadius, "an empty reel still shows tape around the hub")
    }

    @Test
    fun `the empty hub turns once in about 1_2 s and the full one visibly slower`() {
        val emptySecondsPerTurn = 360f / hubDegreesPerSecond(g.RMin)
        val fullSecondsPerTurn = 360f / hubDegreesPerSecond(g.RMax)
        assertTrue(close(emptySecondsPerTurn, 1.2f, 0.001f), "$emptySecondsPerTurn")
        assertTrue(fullSecondsPerTurn > 2f * emptySecondsPerTurn, "$fullSecondsPerTurn")
        // ω·r is the tape speed for every radius
        for (r in listOf(g.RMin, 150f, g.RMax)) {
            val v = hubDegreesPerSecond(r) * (Math.PI.toFloat() / 180f) * r
            assertTrue(close(v, g.TapeSpeed, 0.01f))
        }
    }

    @Test
    fun `hub angle advances ANTICLOCKWISE (falls), wraps into 0 to 360 and caps a long frame`() {
        // DrawScope rotate is clockwise-positive on screen, so an anticlockwise hub's angle falls
        val step = advanceHubAngle(100f, g.RMin, 0.1f)
        assertTrue(close(step, 70f, 0.01f), "$step")          // 300°/s × 0.1 s, backwards
        assertTrue(close(advanceHubAngle(0f, g.RMin, 0.1f), 330f, 0.01f), "wraps below 0")
        assertTrue(close(advanceHubAngle(20f, g.RMin, 0.1f), 350f, 0.01f))
        // both hubs, at every pack size, turn the same way
        for (r in listOf(g.RMin, 160f, g.RMax)) {
            val a = advanceHubAngle(180f, r, 0.05f)
            assertTrue(a < 180f && a >= 0f && a < 360f, "r $r → $a")
        }
        // many small frames stay inside [0, 360)
        var angle = 5f
        repeat(200) {
            angle = advanceHubAngle(angle, g.RMin, 1f / 60f)
            assertTrue(angle >= 0f && angle < 360f, "$angle")
        }
        assertEquals(advanceHubAngle(0f, g.RMin, 0.1f), advanceHubAngle(0f, g.RMin, 5f))
        assertEquals(12f, advanceHubAngle(12f, g.RMin, 0f))
        assertEquals(12f, advanceHubAngle(12f, g.RMin, -1f))
    }

    @Test
    fun `the window is a true stadium around both hubs and the hubs fit inside it`() {
        assertTrue(close(g.WinRadius, (g.WinBottom - g.WinTop) / 2f))
        assertTrue(g.HubY - g.HubRadius >= g.WinTop && g.HubY + g.HubRadius <= g.WinBottom)
        assertTrue(g.HubLeftX - g.HubRadius >= g.WinLeft && g.HubRightX + g.HubRadius <= g.WinRight)
        assertTrue(g.TapeRunY < g.WinBottom && g.TapeRunY > g.HubY)
    }
}

class CassetteLabelTextTest {

    @Test
    fun `descending sizes end exactly on the floor`() {
        assertEquals(listOf(46f, 44f, 42f), descendingSizes(46f, 42f, 2f))
        assertEquals(listOf(27f, 25f, 24f), descendingSizes(27f, 24f, 2f))
        assertEquals(listOf(20f), descendingSizes(20f, 20f, 1f))
        assertEquals(46f, CassetteGeometry.TitleSizes.first())
        assertEquals(24f, CassetteGeometry.TitleSizes.last())
        assertEquals(18f, CassetteGeometry.ArtistSizes.last())
    }

    @Test
    fun `chooser takes the largest size that fits`() {
        val sizes = descendingSizes(46f, 24f, 2f)
        // width grows linearly with size: 10 units per point
        assertEquals(FittedSize(46f, false), chooseFontSize(sizes, 656f) { it * 10f })
        assertEquals(FittedSize(32f, false), chooseFontSize(sizes, 330f) { it * 10f })
        // exactly at the limit fits
        assertEquals(FittedSize(40f, false), chooseFontSize(sizes, 400f) { it * 10f })
    }

    @Test
    fun `chooser ellipsises at the floor when nothing fits`() {
        val sizes = descendingSizes(46f, 24f, 2f)
        assertEquals(FittedSize(24f, true), chooseFontSize(sizes, 100f) { it * 10f })
    }

    @Test
    fun `chooser measures each size at most once and stops at the first fit`() {
        val asked = ArrayList<Float>()
        chooseFontSize(descendingSizes(46f, 24f, 2f), 400f) { asked += it; it * 10f }
        assertEquals(listOf(46f, 44f, 42f, 40f), asked)
    }

    @Test
    fun `candidate sizes skip everything above the linear estimate and keep the floor`() {
        val sizes = descendingSizes(46f, 24f, 2f)
        assertEquals(listOf(46f), candidateSizes(sizes, widthAtFirst = 500f, maxWidth = 656f))
        // 920 wide at 46 → ~32.8 fits → try 32 first (34 is above 32.8 × 1.03 = 33.8)
        assertEquals(32f, candidateSizes(sizes, widthAtFirst = 920f, maxWidth = 656f).first())
        assertEquals(24f, candidateSizes(sizes, widthAtFirst = 920f, maxWidth = 656f).last())
        // hopeless: only the floor is left, and the chooser ellipsises it
        val hopeless = candidateSizes(sizes, widthAtFirst = 5000f, maxWidth = 656f)
        assertEquals(listOf(24f), hopeless)
        assertEquals(FittedSize(24f, true), chooseFontSize(hopeless, 656f) { it * 108f })
        // the estimate agrees with the full walk for a linear width
        val w = { s: Float -> s * 20f }
        assertEquals(chooseFontSize(sizes, 656f, w), chooseFontSize(candidateSizes(sizes, w(46f), 656f), 656f, w))
    }

    @Test
    fun `meta line prints album and year, either half, or nothing`() {
        val join = { a: String, y: String -> "$a · $y" }
        assertEquals("Mass · 2026", cassetteMetaLine("Mass", "2026", join))
        assertEquals("Mass", cassetteMetaLine("Mass", null, join))
        assertEquals("Mass", cassetteMetaLine("Mass", "  ", join))
        assertEquals("2026", cassetteMetaLine(null, "2026", join))
        assertEquals(null, cassetteMetaLine(null, null, join))
        assertEquals(null, cassetteMetaLine(" ", "", join))
    }

    @Test
    fun `fine print prefixes the copyright and is set in capitals`() {
        assertEquals("© 2026 VELVET CO. ALL RIGHTS.", cassetteFinePrint("© 2026 Velvet Co.", "All rights."))
        assertEquals("ALL RIGHTS.", cassetteFinePrint(null, "All rights."))
        assertEquals("ALL RIGHTS.", cassetteFinePrint("   ", "All rights."))
    }

    @Test
    fun `fine print is laid out narrow enough to break in two`() {
        assertTrue(close(finePrintWidth(700f, 760f), 420f))
        assertEquals(760f, finePrintWidth(2000f, 760f))
        assertEquals(200f, finePrintWidth(100f, 760f, minWidth = 200f))
    }
}

class CassetteChoreographyTimelineTest {

    @Test
    fun `flip runs 0 to 180, eased, and a backward flip turns the other way`() {
        assertEquals(0f, flipAngleAt(0f, forward = true))
        assertEquals(180f, flipAngleAt(CassetteTiming.FlipMs.toFloat(), forward = true))
        assertEquals(180f, flipAngleAt(10_000f, forward = true))
        assertEquals(-180f, flipAngleAt(CassetteTiming.FlipMs.toFloat(), forward = false))
        // FastOutSlowIn: past the midpoint of the angle before half the time
        assertTrue(flipAngleAt(CassetteTiming.FlipMs / 2f, forward = true) > 90f)
        // monotone
        var last = -1f
        for (i in 0..50) {
            val a = flipAngleAt(CassetteTiming.FlipMs * i / 50f, forward = true)
            assertTrue(a >= last); last = a
        }
    }

    @Test
    fun `flip swaps faces at 90 degrees and the incoming face lands upright`() {
        assertFalse(flipShowsIncoming(89.9f)); assertTrue(flipShowsIncoming(90f))
        assertFalse(flipShowsIncoming(-89.9f)); assertTrue(flipShowsIncoming(-90f))
        assertEquals(0f, flipFaceRotation(180f, incoming = true))
        assertEquals(0f, flipFaceRotation(-180f, incoming = true))
        assertEquals(-90f, flipFaceRotation(90f, incoming = true))
        assertEquals(90f, flipFaceRotation(-90f, incoming = true))
        assertEquals(45f, flipFaceRotation(45f, incoming = false))
    }

    @Test
    fun `the flip camera sits 12 half-long-sides away in 72 px units`() {
        assertTrue(close(flipCameraDistance(1955f), 12f * 977.5f / 72f, 1e-3f))
        assertTrue(close(flipCameraDistance(2448f), 2 * flipCameraDistance(1224f), 1e-3f))
    }

    @Test
    fun `the flip scale keeps the near end at its rest size at every angle`() {
        val c = FlipCameraHalfSides
        assertEquals(1f, flipNearEdgeScale(0f))
        assertTrue(close(flipNearEdgeScale(180f), 1f, 1e-4f))
        assertTrue(close(flipNearEdgeScale(90f), c / (c + 1f), 1e-5f))
        assertTrue(close(flipNearEdgeScale(-90f), flipNearEdgeScale(90f), 1e-6f))
        for (i in 0..36) {
            val a = i * 5f
            val s = flipNearEdgeScale(a)
            val sin = kotlin.math.abs(kotlin.math.sin(Math.toRadians(a.toDouble()))).toFloat()
            // near end = s × perspective magnification C / (C − s·sin θ): never above 1
            val near = s * c / (c - s * sin)
            assertTrue(near <= 1f + 1e-5f && near > 0.999f, "angle $a → near end $near")
            // the incoming face (pre-rotated 180°) gets the same scale
            assertTrue(close(flipNearEdgeScale(flipFaceRotation(a, incoming = true)), s, 1e-4f))
        }
    }

    @Test
    fun `eject slides out, pauses, slides the new shell in and settles`() {
        val total = EjectTotalMs.toFloat()
        assertEquals(CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs + CassetteTiming.InsertMs, EjectTotalMs)
        val start = ejectFrameAt(0f)
        assertEquals(0f, start.outgoingShift); assertEquals(EjectTravel, start.incomingShift)
        val gap = ejectFrameAt(CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs / 2f)
        assertEquals(EjectTravel, gap.outgoingShift); assertEquals(EjectTravel, gap.incomingShift)
        val end = ejectFrameAt(total)
        assertEquals(EjectTravel, end.outgoingShift)
        assertEquals(0f, end.incomingShift); assertEquals(1f, end.incomingScale)
        assertTrue(close(ejectFrameAt(0f).incomingScale, 0.98f))
        // the outgoing shell is fully gone before the new one starts moving
        assertTrue(EjectTravel > 1f)
        var last = EjectTravel
        for (i in 0..40) {
            val f = ejectFrameAt(total * i / 40f)
            assertTrue(f.incomingShift <= last + 1e-4f); last = f.incomingShift
        }
    }
}

class CassetteStageOrientationTest {

    private fun near(p: Pair<Float, Float>, x: Float, y: Float) =
        close(p.first, x, 1e-4f) && close(p.second, y, 1e-4f)

    @Test
    fun `landscape is the natural orientation`() {
        assertEquals(0f, stageRotationZ(portrait = false))
        assertTrue(near(naturalToScreen(0f, 1f, portrait = false), 0f, 1f))   // head edge down
        assertTrue(near(naturalToScreen(1f, 0f, portrait = false), 1f, 0f))   // eject to the right
    }

    @Test
    fun `portrait turns anticlockwise - head edge right, label reads upward, eject goes up`() {
        assertEquals(-90f, stageRotationZ(portrait = true))
        assertTrue(near(naturalToScreen(0f, 1f, portrait = true), 1f, 0f), "head edge on the RIGHT")
        assertTrue(near(naturalToScreen(1f, 0f, portrait = true), 0f, -1f), "reads bottom-to-top / ejects UP")
    }

    @Test
    fun `the cover screen fills with the shell turned, the unfolded screen letterboxes`() {
        // Fold 8 cover in px: 1248 × 1972
        val cover = cassetteFit(1248f, 1972f)
        assertTrue(cover.portrait)
        assertTrue(close(cover.short, 1248f)); assertTrue(cover.long <= 1972f)
        // After the −90° turn the (long × short) box occupies (short × long) on screen: it fits.
        assertTrue(cover.short <= 1248f + 0.01f && cover.long <= 1972f + 0.01f)
        val unfolded = cassetteFit(2448f, 1848f)
        assertFalse(unfolded.portrait)
        assertTrue(close(unfolded.long, 2448f)); assertTrue(unfolded.short < 1848f)
    }
}
