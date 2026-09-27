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
        assertTrue(close(step, 70f, 0.01f), "$step")          // 300°/s × 0.1 s, the angle falling
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
    fun `flip always runs 0 to plus 180, eased and monotone`() {
        assertEquals(0f, flipAngleAt(0f))
        assertEquals(0f, flipAngleAt(-50f))
        assertEquals(180f, flipAngleAt(CassetteTiming.FlipMs.toFloat()))
        assertEquals(180f, flipAngleAt(10_000f))
        // FastOutSlowIn: past the midpoint of the angle before half the time
        assertTrue(flipAngleAt(CassetteTiming.FlipMs / 2f) > 90f)
        // monotone, never negative
        var last = 0f
        for (i in 0..50) {
            val a = flipAngleAt(CassetteTiming.FlipMs * i / 50f)
            assertTrue(a >= last); last = a
        }
    }

    @Test
    fun `flip swaps faces at 90 degrees and the incoming face lands upright`() {
        assertFalse(flipShowsIncoming(89.9f)); assertTrue(flipShowsIncoming(90f))
        assertEquals(0f, flipFaceRotation(180f, incoming = true))
        assertEquals(-90f, flipFaceRotation(90f, incoming = true))
        assertEquals(-180f, flipFaceRotation(0f, incoming = true))
        assertEquals(45f, flipFaceRotation(45f, incoming = false))
        // at the swap the two faces are edge-on together (±90° are the same plane)
        assertEquals(90f, flipFaceRotation(90f, incoming = false))
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
        assertEquals(0f, start.outgoingShift); assertEquals(1f, start.incomingShift)
        val gap = ejectFrameAt(CassetteTiming.EjectOutMs + CassetteTiming.EjectGapMs / 2f)
        assertEquals(1f, gap.outgoingShift); assertEquals(1f, gap.incomingShift)
        val end = ejectFrameAt(total)
        assertEquals(1f, end.outgoingShift)
        assertEquals(0f, end.incomingShift); assertEquals(1f, end.incomingScale)
        assertTrue(close(ejectFrameAt(0f).incomingScale, 0.98f))
        // the incoming shell only ever approaches, the outgoing one only ever leaves
        var lastIn = 1f
        var lastOut = 0f
        for (i in 0..40) {
            val f = ejectFrameAt(total * i / 40f)
            assertTrue(f.incomingShift <= lastIn + 1e-4f); lastIn = f.incomingShift
            assertTrue(f.outgoingShift >= lastOut - 1e-4f); lastOut = f.outgoingShift
        }
        // the new shell does not start moving until the old one is all the way out
        for (i in 0..40) {
            val f = ejectFrameAt(total * i / 40f)
            if (f.incomingShift < 1f) assertEquals(1f, f.outgoingShift, "at ${total * i / 40f} ms")
        }
    }

    @Test
    fun `eject travel is the short side plus the band plus a 4 percent margin`() {
        assertTrue(close(ejectTravel(1000f, 0f), 1040f))
        assertTrue(close(ejectTravel(1000f, 150f), 1190f))
        assertTrue(close(ejectTravel(1000f, -5f), 1040f), "a negative band reads as 0")
    }

    @Test
    fun `the eject band is the letterbox on the side the shell leaves through`() {
        // Fold 8 cover, portrait 1248 × 1972: full bleed on the width → no band
        val cover = cassetteFit(1248f, 1972f)
        assertTrue(close(ejectBand(1248f, 1972f, cover.short, cover.portrait), 0f, 0.5f))
        // the cover in landscape (user_rotation 1): full bleed on the height
        val coverLand = cassetteFit(1972f, 1248f)
        assertTrue(close(ejectBand(1972f, 1248f, coverLand.short, coverLand.portrait), 0f, 0.5f))
        // unfolded 2448 × 1848: the shell is 1562 tall → ~143 px above and below
        val unfolded = cassetteFit(2448f, 1848f)
        val band = ejectBand(2448f, 1848f, unfolded.short, unfolded.portrait)
        assertTrue(close(band, (1848f - unfolded.short) / 2f, 1e-3f) && band > 140f, "$band")
        // and there the shell clears the top of the stage entirely: its bottom edge, which rests
        // band + short below the top, ends above it
        val travel = ejectTravel(unfolded.short, band)
        val bottom = band + unfolded.short - travel
        assertTrue(bottom < -0.04f * unfolded.short + 1e-2f, "bottom edge at $bottom")
    }
}

class CassetteStageOrientationTest {

    private fun near(p: Pair<Float, Float>, x: Float, y: Float) =
        close(p.first, x, 1e-4f) && close(p.second, y, 1e-4f)

    @Test
    fun `landscape is the natural orientation`() {
        assertEquals(0f, stageRotationZ(portrait = false))
        assertTrue(near(naturalToScreen(0f, 1f, portrait = false), 0f, 1f))   // head edge down
        assertTrue(near(naturalToScreen(0f, -1f, portrait = false), 0f, -1f)) // eject through the TOP
    }

    @Test
    fun `portrait turns anticlockwise - head edge right, label reads upward, eject goes left`() {
        assertEquals(-90f, stageRotationZ(portrait = true))
        assertTrue(near(naturalToScreen(0f, 1f, portrait = true), 1f, 0f), "head edge on the RIGHT")
        assertTrue(near(naturalToScreen(1f, 0f, portrait = true), 0f, -1f), "reads bottom-to-top")
        assertTrue(near(naturalToScreen(0f, -1f, portrait = true), -1f, 0f), "ejects through the title edge, LEFT")
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

class CassettePackPeekTest {

    private val g = CassetteGeometry

    @Test
    fun `the peek band runs from under the label shadow to the head cavity, inside the inner wall`() {
        assertEquals(g.LabelShadowBottom, g.PeekTop)
        assertTrue(g.PeekTop > g.LabelBottom)
        assertTrue(g.PeekFadeTop > g.PeekTop && g.PeekFadeTop < g.PeekBottom)
        assertEquals(500f, g.PeekBottom)                     // the cavity path's top edge
        // the inner moulded wall is 18..982 × 18..620 with r 14: the band is on its straight run
        assertTrue(g.PeekLeft >= 18f && g.PeekRight <= 982f && g.PeekBottom <= 620f - 14f)
    }

    @Test
    fun `peek depth is zero until a pack clears the label, then grows, clamped to the band`() {
        assertEquals(0f, packPeekDepth(g.RMin))
        assertEquals(0f, packPeekDepth(g.PeekTop - g.HubY))
        assertEquals(g.PeekBottom - g.PeekTop, packPeekDepth(g.RMax))   // a full pack reaches 540
        var last = 0f
        for (i in 0..100) {
            val d = packPeekDepth(g.RMin + (g.RMax - g.RMin) * i / 100f)
            assertTrue(d >= last); last = d
        }
    }

    @Test
    fun `the fuller pack peeks at either end of a song and both only barely at the middle`() {
        val start = packRadii(0f)
        assertTrue(packPeekDepth(start.supply) > 0f); assertEquals(0f, packPeekDepth(start.takeUp))
        val end = packRadii(1f)
        assertEquals(0f, packPeekDepth(end.supply)); assertTrue(packPeekDepth(end.takeUp) > 0f)
        val mid = packRadii(0.5f)
        assertTrue(packPeekDepth(mid.supply) in 0f..15f && packPeekDepth(mid.takeUp) in 0f..15f)
    }

    /** Mirrors CassetteArt's head-edge group and screws: (cx, cy, r) of everything that must never
     *  have tape drawn over it, and the head recess as a rect. */
    private val cleanCircles = listOf(
        Triple(272f, 590f, 27f), Triple(728f, 590f, 27f),     // pinch rollers
        Triple(122f, 557f, 46f), Triple(878f, 557f, 46f),     // guide rollers
        Triple(367f, 573f, 28f), Triple(633f, 573f, 28f),     // capstan holes
        Triple(34f, 604f, 18f), Triple(966f, 604f, 18f),      // bottom corner screws (countersink)
        Triple(34f, 34f, 18f), Triple(966f, 34f, 18f),        // top corner screws
        Triple(500f, 530f, 19f),                              // head screw + its boss
    )

    @Test
    fun `no peeking tape can land on a roller, the head recess or a screw`() {
        var sampled = 0
        var y = g.PeekTop
        while (y <= g.PeekBottom) {
            var x = g.PeekLeft
            while (x <= g.PeekRight) {
                val inPack = listOf(g.HubLeftX, g.HubRightX).any { hx ->
                    (x - hx) * (x - hx) + (y - g.HubY) * (y - g.HubY) <= g.RMax * g.RMax
                }
                if (inPack) {
                    sampled++
                    for ((cx, cy, r) in cleanCircles) {
                        assertTrue((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r, "tape at ($x, $y) over ($cx, $cy)")
                    }
                    assertFalse(x in 418f..582f && y in 552f..637f, "tape at ($x, $y) over the head recess")
                }
                x += 1f
            }
            y += 0.5f
        }
        assertTrue(sampled > 1000, "the band must actually overlap a full pack ($sampled)")
    }
}
