package com.crsmthw.lyra.ui.cassette

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `a` and `b` are the same angle, modulo a full turn. */
private fun sameAngle(a: Float, b: Float, eps: Float = 1e-3f): Boolean {
    val d = ((a - b) % 360f + 360f) % 360f
    return d <= eps || d >= 360f - eps
}

/** Surface.ROTATION_0..270 — the values Display.getRotation() returns. */
private const val R0 = 0
private const val R90 = 1
private const val R180 = 2
private const val R270 = 3

class CassetteRotationAngleTest {

    @Test
    fun `content rotation is the Surface rotation times 90, clamped`() {
        assertEquals(0f, contentRotationDeg(R0))
        assertEquals(90f, contentRotationDeg(R90))
        assertEquals(180f, contentRotationDeg(R180))
        assertEquals(270f, contentRotationDeg(R270))
        assertEquals(0f, contentRotationDeg(-1))
        assertEquals(270f, contentRotationDeg(4))
        assertEquals(270f, contentRotationDeg(Int.MAX_VALUE))
    }

    @Test
    fun `the image on the glass - portrait upright and clockwise landscape agree, anticlockwise is flipped`() {
        // portrait at ROTATION_0: the head edge on the glass's right
        assertEquals(270f, glassAngleDeg(portrait = true, surfaceRotation = R0))
        // phone turned CLOCKWISE → ROTATION_270, landscape: the same place on the glass
        assertEquals(270f, glassAngleDeg(portrait = false, surfaceRotation = R270))
        // phone turned ANTICLOCKWISE → ROTATION_90, landscape: the head edge on the other side
        assertEquals(90f, glassAngleDeg(portrait = false, surfaceRotation = R90))
        // upside-down portrait agrees with the anticlockwise landscape
        assertEquals(90f, glassAngleDeg(portrait = true, surfaceRotation = R180))
        // a landscape-natural screen (ROTATION_0 landscape) is the natural drawing
        assertEquals(0f, glassAngleDeg(portrait = false, surfaceRotation = R0))
        for (r in -2..6) for (p in listOf(true, false)) {
            val g = glassAngleDeg(p, r)
            assertTrue(g >= 0f && g < 360f, "($p, $r) → $g")
        }
    }
}

class CassetteOrientationTurnTest {

    @Test
    fun `clockwise out of portrait and back - the image stays put, nothing settles`() {
        assertEquals(OrientationTurn(0f, portrait = false), orientationTurnFor(R0, lastPortrait = true, newRotation = R270))
        assertEquals(OrientationTurn(0f, portrait = true), orientationTurnFor(R270, lastPortrait = false, newRotation = R0))
    }

    @Test
    fun `anticlockwise out of portrait settles clockwise from minus 180, back settles from plus 180`() {
        assertEquals(OrientationTurn(-180f, portrait = false), orientationTurnFor(R0, lastPortrait = true, newRotation = R90))
        assertEquals(OrientationTurn(180f, portrait = true), orientationTurnFor(R90, lastPortrait = false, newRotation = R0))
    }

    @Test
    fun `a landscape phone turned over settles 180 either way`() {
        val over = orientationTurnFor(R90, lastPortrait = false, newRotation = R270)
        assertEquals(OrientationTurn(-180f, portrait = false), over)
        val back = orientationTurnFor(R270, lastPortrait = false, newRotation = R90)
        assertEquals(180f, abs(back.startDeg)); assertFalse(back.portrait)
        // a half turn of the content wraps to +180, i.e. clockwise: −180
        assertEquals(-180f, back.startDeg)
    }

    @Test
    fun `upside-down portrait transitions`() {
        assertEquals(OrientationTurn(-180f, portrait = true), orientationTurnFor(R0, lastPortrait = true, newRotation = R180))
        assertEquals(OrientationTurn(-180f, portrait = true), orientationTurnFor(R180, lastPortrait = true, newRotation = R0))
        assertEquals(OrientationTurn(0f, portrait = false), orientationTurnFor(R180, lastPortrait = true, newRotation = R90))
        assertEquals(OrientationTurn(-180f, portrait = false), orientationTurnFor(R180, lastPortrait = true, newRotation = R270))
        assertEquals(OrientationTurn(0f, portrait = true), orientationTurnFor(R90, lastPortrait = false, newRotation = R180))
        assertEquals(OrientationTurn(180f, portrait = true), orientationTurnFor(R270, lastPortrait = false, newRotation = R180))
    }

    @Test
    fun `the same rotation is never a turn and keeps the orientation`() {
        for (r in R0..R270) for (p in listOf(true, false)) {
            assertEquals(OrientationTurn(0f, portrait = p), orientationTurnFor(r, p, r))
        }
    }

    @Test
    fun `every transition - the first frame lands where the old one was, and the direction follows the content`() {
        for (last in R0..R270) for (lastPortrait in listOf(true, false)) for (new in R0..R270) {
            val t = orientationTurnFor(last, lastPortrait, new)
            val quarter = (new - last).mod(2) == 1
            assertEquals(if (quarter) !lastPortrait else lastPortrait, t.portrait, "($last, $lastPortrait) → $new")
            assertTrue(t.startDeg == 0f || abs(t.startDeg) == 180f, "($last, $lastPortrait) → $new: ${t.startDeg}")
            // the new frame, drawn with the start turn, shows the shell where the old frame did
            val before = glassAngleDeg(lastPortrait, last)
            val after = glassAngleDeg(t.portrait, new) + t.startDeg
            assertTrue(sameAngle(before, after), "($last, $lastPortrait) → $new: $before vs $after")
            // nothing to settle exactly when the image did not move
            assertEquals(sameAngle(before, glassAngleDeg(t.portrait, new)), t.startDeg == 0f)
            if (t.startDeg != 0f) {
                val content = ((new - last).mod(4)).let { if (it == 3) -1 else it }   // −1, 1 or 2 quarter turns
                assertEquals(if (content < 0) 180f else -180f, t.startDeg, "($last, $lastPortrait) → $new")
            }
        }
    }

    @Test
    fun `out-of-range rotations are clamped, never a crash`() {
        assertEquals(orientationTurnFor(R0, true, R270), orientationTurnFor(-5, true, 9))
    }
}

class CassetteOrientationTurnStartTest {

    @Test
    fun `at rest the start is the turn itself, sign kept`() {
        assertEquals(-180f, orientationTurnStart(-180f, 0f))
        assertEquals(180f, orientationTurnStart(180f, 0f))
    }

    @Test
    fun `a settle in flight is carried, the short way round`() {
        // turned back right after turning: the shell had barely left −180, so it barely moves back
        assertEquals(10f, orientationTurnStart(-180f, -170f), 1e-4f)
        assertEquals(-170f, orientationTurnStart(180f, 10f), 1e-4f)
        assertEquals(-90f, orientationTurnStart(-180f, 90f), 1e-4f)
        assertEquals(0f, orientationTurnStart(180f, -180f), 1e-4f)
        var inFlight = -180f
        while (inFlight <= 180f) {
            for (start in listOf(-180f, 180f)) {
                val s = orientationTurnStart(start, inFlight)
                assertTrue(s >= -180f && s <= 180f, "$start + $inFlight → $s")
                assertTrue(sameAngle(s, start + inFlight), "$start + $inFlight → $s")
            }
            inFlight += 5f
        }
    }
}

/**
 * Every `update` below is ONE composition of the stage, fed what the stage now feeds it: the display
 * rotation read FRESH, and the Compose Configuration's orientation and smallest width, which may be
 * one recomposer pass behind that rotation (a relayout of our own that hands back the rotation
 * mid-traversal) but never ahead of it. The fit's orientation, from the constraints, is not an input.
 */
class CassetteOrientationTurnStateTest {

    private val noSettle = { 0f }
    private val sw = 475   // the cover screen's smallest width, dp

    @Test
    fun `the first composition only records`() {
        val s = OrientationTurnState()
        assertFalse(s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(R0, s.rotation); assertTrue(s.portrait); assertEquals(0, s.generation)
    }

    @Test
    fun `the resize message - the rotation and its configuration together - one settle`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertTrue(s.update(R90, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(1, s.generation); assertEquals(-180f, s.from); assertFalse(s.portrait)
        // the measure pass with the new constraints composes the same inputs: no second settle
        assertFalse(s.update(R90, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(1, s.generation); assertEquals(-180f, s.from); assertFalse(s.portrait)
    }

    @Test
    fun `a relayout of our own - anticlockwise, the fresh rotation under the stale configuration - one settle`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        // the measure pass: the new constraints and the fresh rotation, LocalConfiguration still
        // portrait. The orientation is ignored at a rotation change; the settle starts right here,
        // so the first frame in the new rotation already carries it
        assertTrue(s.update(R90, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(1, s.generation); assertEquals(-180f, s.from); assertFalse(s.portrait)
        // the recomposer's next pass: the configuration lands, a re-sync to what was predicted
        assertFalse(s.update(R90, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(1, s.generation); assertEquals(-180f, s.from); assertFalse(s.portrait)
    }

    @Test
    fun `a relayout of our own - clockwise under the stale configuration settles nothing, nor does the way back`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertFalse(s.update(R270, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertFalse(s.portrait); assertEquals(0, s.generation)
        assertFalse(s.update(R270, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertFalse(s.portrait); assertEquals(0, s.generation)
        // back to portrait the same way: judged from the landscape baseline, the image stays put
        assertFalse(s.update(R0, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertTrue(s.portrait); assertEquals(0, s.generation)
        assertFalse(s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertTrue(s.portrait); assertEquals(0, s.generation)
    }

    @Test
    fun `a composition that changes nothing the bookkeeping reads is a no-op, and the rotation after it is judged right`() {
        // Review finding, 2026-09-26: a measure pass that composed NEW constraints under the OLD
        // Configuration re-synced the baseline to the new orientation at the old rotation, so the
        // clockwise turn then spun and the anticlockwise one jump-cut. The constraints are no longer
        // an input; such a pass repeats what the bookkeeping already has.
        val clockwise = OrientationTurnState()
        clockwise.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertFalse(clockwise.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertFalse(clockwise.update(R270, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(0, clockwise.generation); assertFalse(clockwise.portrait)

        val anticlockwise = OrientationTurnState()
        anticlockwise.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertFalse(anticlockwise.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertTrue(anticlockwise.update(R90, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(1, anticlockwise.generation); assertEquals(-180f, anticlockwise.from); assertFalse(anticlockwise.portrait)
    }

    @Test
    fun `clockwise and back settle nothing`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertFalse(s.update(R270, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertFalse(s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertEquals(0, s.generation); assertTrue(s.portrait)
    }

    @Test
    fun `a size-only change re-syncs the orientation and is never a turn`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        // unfolded, rotation unchanged; the new constraints may compose first, under the old
        // configuration: nothing the bookkeeping reads has changed yet
        assertFalse(s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle))
        assertTrue(s.portrait); assertEquals(0, s.generation)
        // the configuration: the window is landscape now
        assertFalse(s.update(R0, configPortrait = false, smallestWidthDp = 704, inFlightDeg = noSettle))
        assertFalse(s.portrait); assertEquals(0, s.generation)
        // and the NEXT rotation is judged from the landscape it re-synced to: landscape R0 → R90 is
        // portrait, the image stays put on the glass (0 → −90 + 90)
        assertFalse(s.update(R90, configPortrait = true, smallestWidthDp = 704, inFlightDeg = noSettle))
        assertTrue(s.portrait); assertEquals(0, s.generation)
    }

    @Test
    fun `a rotation that also changes the smallest width is a fold, not a turn`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        // unfolded AND rotated: one Configuration carries the new orientation and smallest width
        assertFalse(s.update(R90, configPortrait = false, smallestWidthDp = 704, inFlightDeg = noSettle))
        assertEquals(0, s.generation); assertEquals(R90, s.rotation); assertFalse(s.portrait)
        // the baseline is that Configuration's: the unfolded screen then turned back to R0 (content
        // −90) is portrait, and the image flips on the glass (90 → 270), settling from +180
        assertTrue(s.update(R0, configPortrait = true, smallestWidthDp = 704, inFlightDeg = noSettle))
        assertEquals(1, s.generation); assertEquals(180f, s.from); assertTrue(s.portrait)
    }

    @Test
    fun `a rotation mid-settle carries what is left of the settle`() {
        val s = OrientationTurnState()
        s.update(R0, configPortrait = true, smallestWidthDp = sw, inFlightDeg = noSettle)
        assertTrue(s.update(R90, configPortrait = false, smallestWidthDp = sw, inFlightDeg = noSettle))
        // turned back while the shell is still 170° from rest (it had barely moved)
        var asked = 0
        assertTrue(s.update(R0, configPortrait = true, smallestWidthDp = sw) { asked++; -170f })
        assertEquals(1, asked); assertEquals(2, s.generation); assertEquals(10f, s.from, 1e-4f)
        // a rotation that leaves the image put never reads the running settle and starts none
        assertFalse(s.update(R270, configPortrait = false, smallestWidthDp = sw) { asked++; -90f })
        assertEquals(1, asked); assertEquals(2, s.generation)
    }
}

class CassetteSpinFitScaleTest {

    private val landW = 1972f
    private val landH = 1248f
    private val land = cassetteFit(landW, landH)            // the cover screen, landscape (px)
    private val port = cassetteFit(landH, landW)            // the cover screen, portrait (px)

    @Test
    fun `at both rest angles a full-bleed shell is at full size`() {
        assertEquals(1f, spinFitScale(0f, land.long, land.short, landW, landH), 1e-5f)
        assertEquals(1f, spinFitScale(-90f, port.long, port.short, landH, landW), 1e-5f)
        // a half turn away is aligned with the window again
        assertEquals(1f, spinFitScale(-180f, land.long, land.short, landW, landH), 1e-5f)
        assertEquals(1f, spinFitScale(90f, port.long, port.short, landH, landW), 1e-5f)
        // the unfolded screen letterboxes: the shell fits with room, exactly 1
        val unfolded = cassetteFit(2448f, 1848f)
        assertEquals(1f, spinFitScale(0f, unfolded.long, unfolded.short, 2448f, 1848f))
    }

    @Test
    fun `crosswise the shell shrinks to short over long`() {
        val ratio = 1f / CASSETTE_ASPECT
        assertEquals(ratio, spinFitScale(90f, land.long, land.short, landW, landH), 1e-3f)
        assertEquals(ratio, spinFitScale(-90f, land.long, land.short, landW, landH), 1e-3f)
        assertEquals(ratio, spinFitScale(0f, port.long, port.short, landH, landW), 1e-3f)
        assertEquals(0.638f, ratio, 1e-3f)
    }

    @Test
    fun `never above 1 and symmetric about the midpoint of the settle`() {
        for (i in 0..72) {
            val a = i * 5f
            assertTrue(spinFitScale(a, land.long, land.short, landW, landH) <= 1f)
            assertTrue(spinFitScale(-a, port.long, port.short, landH, landW) <= 1f)
        }
        for (d in 0..90 step 5) {
            val left = spinFitScale(-90f - d, land.long, land.short, landW, landH)
            val right = spinFitScale(-90f + d, land.long, land.short, landW, landH)
            assertEquals(left, right, 1e-5f, "±$d about −90")
            val pl = spinFitScale(-180f - d, port.long, port.short, landH, landW)
            val pr = spinFitScale(-180f + d, port.long, port.short, landH, landW)
            assertEquals(pl, pr, 1e-5f, "±$d about −180 (portrait)")
        }
    }

    @Test
    fun `at every angle the turned shell fits the window, and is as large as it can be`() {
        for ((fit, w, h) in listOf(Triple(land, landW, landH), Triple(port, landH, landW), Triple(cassetteFit(2448f, 1848f), 2448f, 1848f))) {
            for (i in 0..72) {
                val a = i * 5f
                val k = spinFitScale(a, fit.long, fit.short, w, h)
                val rad = Math.toRadians(a.toDouble())
                val c = abs(cos(rad)).toFloat()
                val s = abs(sin(rad)).toFloat()
                val bw = k * (fit.long * c + fit.short * s)
                val bh = k * (fit.long * s + fit.short * c)
                assertTrue(bw <= w + 1e-3f && bh <= h + 1e-3f, "$a°: ${bw}×$bh in ${w}×$h")
                // the largest such scale: one side touches, unless the shell is at full size
                assertTrue(k == 1f || abs(bw - w) < 1e-2f || abs(bh - h) < 1e-2f, "$a°: k $k, ${bw}×$bh in ${w}×$h")
            }
        }
    }

    @Test
    fun `an empty box never divides by zero`() {
        assertEquals(1f, spinFitScale(45f, 0f, 0f, 0f, 0f))
        assertEquals(0f, spinFitScale(45f, 100f, 60f, 0f, 0f))
    }
}

class CassetteSettleScaleTest {

    private val landW = 1972f
    private val landH = 1248f
    private val land = cassetteFit(landW, landH)            // the cover screen, landscape (px)
    private val port = cassetteFit(landH, landW)            // the cover screen, portrait (px)

    @Test
    fun `exactly 1 at rest, whatever the window`() {
        assertEquals(1f, settleScale(portrait = false, extraDeg = 0f, land.long, land.short, landW, landH))
        assertEquals(1f, settleScale(portrait = true, extraDeg = 0f, port.long, port.short, landH, landW))
        // rest is never rescaled, not even in a window the shell would overflow
        assertEquals(1f, settleScale(portrait = false, extraDeg = 0f, long = 1000f, short = 638f, stageW = 10f, stageH = 10f))
        assertEquals(1f, settleScale(portrait = false, extraDeg = -0f, land.long, land.short, landW, landH))
    }

    @Test
    fun `during a settle it is the fit of the shell at its rest turn plus the extra`() {
        for (extra in listOf(-180f, -135f, -90f, -45f, -1f, 1f, 45f, 90f, 135f, 180f)) {
            assertEquals(spinFitScale(extra, land.long, land.short, landW, landH), settleScale(false, extra, land.long, land.short, landW, landH))
            assertEquals(spinFitScale(-90f + extra, port.long, port.short, landH, landW), settleScale(true, extra, port.long, port.short, landH, landW))
        }
        assertEquals(1f / CASSETTE_ASPECT, settleScale(false, -90f, land.long, land.short, landW, landH), 1e-3f)
    }

    @Test
    fun `a ratio - the stage's dp and the eject's px agree`() {
        val density = 2.625f
        val dpFit = cassetteFit(landW / density, landH / density)
        for (extra in listOf(-150f, -90f, -30f, 30f, 90f, 150f)) {
            val px = settleScale(false, extra, land.long, land.short, landW, landH)
            val dp = settleScale(false, extra, dpFit.long, dpFit.short, landW / density, landH / density)
            assertEquals(px, dp, 1e-5f, "$extra°")
        }
    }
}

class CassetteEjectTravelTurnedTest {

    /** The stages the cassette is shown on, px: the cover screen both ways, and the emulator's
     *  unfolded ratio (letterboxed) both ways. */
    private val stages = listOf(1972f to 1248f, 1248f to 1972f, 2448f to 1848f, 1848f to 2448f)

    /**
     * How far the PARKED shell (shift 1, `travel` out) is past the stage along the eject axis, px on
     * screen; negative = still over the stage. Independent of the travel formula: the shell's four
     * corners are placed the way the layers place them — the face translates by −travel along its
     * natural y, then the stage layer scales by `k` and turns by `zDeg` about the shared centre (y
     * down, clockwise-positive like rotationZ) — and separated from the stage's four corners along
     * u = the turned natural −y = (sin z, −cos z).
     */
    private fun clearance(long: Float, short: Float, travel: Float, zDeg: Float, k: Float, w: Float, h: Float): Double {
        val rad = Math.toRadians(zDeg.toDouble())
        val c = cos(rad)
        val s = sin(rad)
        val ux = s
        val uy = -c
        var shellNear = Double.MAX_VALUE
        var stageFar = -Double.MAX_VALUE
        for (fx in listOf(-0.5, 0.5)) for (fy in listOf(-0.5, 0.5)) {
            val lx = fx * long * k
            val ly = (fy * short - travel) * k
            shellNear = minOf(shellNear, (lx * c - ly * s) * ux + (lx * s + ly * c) * uy)
            stageFar = maxOf(stageFar, fx * w * ux + fy * h * uy)
        }
        return shellNear - stageFar
    }

    @Test
    fun `at a rest angle it is the plain travel, letterbox band included`() {
        for ((w, h) in stages) {
            val fit = cassetteFit(w, h)
            val plain = ejectTravel(fit.short, ejectBand(w, h, fit.short, fit.portrait))
            val rest = stageRotationZ(fit.portrait)
            assertEquals(plain, ejectTravelTurned(fit.short, rest, k = 1f, stageW = w, stageH = h), 1e-3f, "${w}×$h")
            // a half turn from rest is aligned with the stage again
            assertEquals(plain, ejectTravelTurned(fit.short, rest + 180f, k = 1f, stageW = w, stageH = h), 1e-3f, "${w}×$h, +180")
        }
    }

    @Test
    fun `at every angle of a settle the parked shell is clear of the stage by exactly the margin`() {
        for ((w, h) in stages) {
            val fit = cassetteFit(w, h)
            for (i in 0..72) {
                val z = i * 5f
                val k = spinFitScale(z, fit.long, fit.short, w, h)
                val travel = ejectTravelTurned(fit.short, z, k, w, h)
                val gap = clearance(fit.long, fit.short, travel, z, k, w, h)
                assertTrue(gap >= -1e-3, "${w}×$h at $z°: ${gap}px")
                assertEquals(k * fit.short * EjectMargin.toDouble(), gap, 1e-2, "${w}×$h at $z°")
            }
        }
    }

    @Test
    fun `the rest travel would have left the parked shell over the turned stage - the review's case`() {
        // the cover screen in landscape, crosswise mid-settle: the shell at k ≈ 0.638
        val (w, h) = 1972f to 1248f
        val fit = cassetteFit(w, h)
        val z = 90f
        val k = spinFitScale(z, fit.long, fit.short, w, h)
        val plain = ejectTravel(fit.short, ejectBand(w, h, fit.short, fit.portrait))
        val before = clearance(fit.long, fit.short, plain, z, k, w, h)
        assertTrue(before < -500.0, "the plain travel parked the shell ${-before}px inside the stage")
        val after = clearance(fit.long, fit.short, ejectTravelTurned(fit.short, z, k, w, h), z, k, w, h)
        assertTrue(after > 0.0, "$after")
    }

    @Test
    fun `an empty stage never divides by zero`() {
        // spinFitScale reads 0 for an empty window; that k is taken as 1
        val k = spinFitScale(45f, 100f, 60f, 0f, 0f)
        assertEquals(0f, k)
        assertEquals(60f * (0.5f + EjectMargin), ejectTravelTurned(short = 60f, zDeg = 45f, k = k, stageW = 0f, stageH = 0f), 1e-4f)
        val negative = ejectTravelTurned(short = 60f, zDeg = 45f, k = -1f, stageW = 10f, stageH = 10f)
        assertTrue(negative.isFinite(), "$negative")
    }
}
