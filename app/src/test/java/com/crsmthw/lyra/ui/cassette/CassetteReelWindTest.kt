package com.crsmthw.lyra.ui.cassette

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** One 60 Hz frame, ns. */
private const val Frame60 = 16_666_667L

/** A four-minute song, ms. */
private const val Track240 = 240_000L

private fun close(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps

/** A hub-angle difference unwrapped into (−180, 180]. */
private fun unwrap(d: Float): Float {
    var x = d % 360f
    if (x > 180f) x -= 360f
    if (x <= -180f) x += 360f
    return x
}

/** A tracker already primed at `p` (its first value only primes). */
private fun primed(p: Float, durationMs: Long = Track240) = ReelTracker(p).also { it.onProgress(p, durationMs) }

/** A 60 Hz frame clock driving a tracker the way the face's frame loop does. */
private class Clock(var now: Long = 1_000_000_000L) {
    fun frame(t: ReelTracker, timeScale: Float = 1f): Float = t.onFrame(now, timeScale).also { now += Frame60 }

    /** Frames until the running wind ends, counting the frame that ends it. */
    fun toEnd(t: ReelTracker, timeScale: Float = 1f): Int {
        var n = 0
        while (t.winding) {
            frame(t, timeScale)
            n++
            check(n < 100_000) { "the wind never ended" }
        }
        return n
    }
}

class CassetteReelWindTest {

    private val g = CassetteGeometry
    private val w = CassetteWind

    // ── isReelJump ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `on an ordinary song a jump needs 5 s of track time`() {
        val at = 0.5f
        assertFalse(isReelJump(at, at + 1_000f / Track240, Track240), "a tick")
        assertFalse(isReelJump(at, at + 4_900f / Track240, Track240), "a poll's correction")
        assertTrue(isReelJump(at, at + 5_100f / Track240, Track240))
        assertTrue(isReelJump(at, at - 5_100f / Track240, Track240), "a rewind is a jump too")
        assertTrue(isReelJump(0.99f, 0.004f, Track240), "a repeat-one wrap")
    }

    @Test
    fun `on a long track the 1 percent floor decides, on a short one the 5 s rule`() {
        val hour = 3_600_000L
        assertFalse(isReelJump(0.5f, 0.5f + 10_000f / hour, hour), "10 s of an hour is under 1 %")
        assertTrue(isReelJump(0.5f, 0.5f + 40_000f / hour, hour))
        val short = 30_000L
        assertFalse(isReelJump(0.5f, 0.5f + 1_000f / short, short), "a tick is 3.3 % of this tape")
    }

    @Test
    fun `no duration and NaN are never a jump`() {
        assertFalse(isReelJump(0.1f, 0.9f, 0L))
        assertFalse(isReelJump(0.1f, 0.9f, -5L))
        assertFalse(isReelJump(Float.NaN, 0.9f, Track240))
        assertFalse(isReelJump(0.1f, Float.NaN, Track240))
    }

    // ── windDurationMs ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `wind duration grows with the square root of the pack distance`() {
        assertEquals(350, windDurationMs(0f))
        assertEquals(900, windDurationMs(1f))
        assertEquals(625, windDurationMs(0.25f))
        assertEquals(405, windDurationMs(0.01f))
        assertEquals(442, windDurationMs(0.028f))            // 5 s of a 3-minute song
        assertEquals(CassetteTiming.WindMinMs, windDurationMs(0f))
        assertEquals(CassetteTiming.WindMaxMs, windDurationMs(1f))
        var last = 0
        for (i in 0..1000) {
            val d = i / 1000f
            val ms = windDurationMs(d)
            assertEquals(ms, windDurationMs(-d), "symmetric at $d")
            assertTrue(ms >= last, "monotone at $d"); last = ms
        }
    }

    @Test
    fun `wind duration reads NaN as the shortest and caps the distance at the whole tape`() {
        assertEquals(350, windDurationMs(Float.NaN))
        assertEquals(900, windDurationMs(7f))
        assertEquals(900, windDurationMs(-7f))
    }

    // ── windEase / windSpeed ───────────────────────────────────────────────────────────────────

    @Test
    fun `the ease starts and ends exactly and clamps outside the timeline`() {
        assertEquals(0f, windEase(0f))
        assertEquals(1f, windEase(1f))
        assertEquals(0f, windEase(-0.5f))
        assertEquals(1f, windEase(1.5f))
        assertEquals(0f, windEase(Float.NaN))
    }

    @Test
    fun `the ease is monotone and continuous at both joints`() {
        var last = windEase(0f)
        for (i in 1..1000) {
            val e = windEase(i / 1000f)
            assertTrue(e >= last, "monotone at ${i / 1000f}: $e < $last"); last = e
        }
        val eps = 1e-6f
        for (joint in listOf(w.WindRampFraction, w.WindBrakeStart)) {
            assertTrue(abs(windEase(joint + eps) - windEase(joint - eps)) < 1e-5f, "continuous at $joint")
        }
        assertTrue(close(w.WindPeakSpeed, 1.4286f, 1e-4f))
    }

    @Test
    fun `the ease's slope is the peak speed times the trapezoid`() {
        val h = 1e-3f
        for (i in 1..99) {
            val f = i / 100f
            val nearJoint = listOf(w.WindRampFraction, w.WindBrakeStart).any { abs(f - it) < 2 * h }
            if (nearJoint) continue
            val slope = (windEase(f + h) - windEase(f - h)) / (2 * h)
            assertTrue(close(slope, w.WindPeakSpeed * windSpeed(f), 1e-2f), "slope at $f: $slope")
        }
    }

    @Test
    fun `the speed is a trapezoid - 0 at the ends, 1 on the cruise, never above 1`() {
        assertEquals(0f, windSpeed(0f))
        assertEquals(0f, windSpeed(1f))
        assertEquals(0f, windSpeed(-0.2f))
        assertEquals(0f, windSpeed(1.2f))
        assertEquals(0f, windSpeed(Float.NaN))
        assertEquals(1f, windSpeed(w.WindRampFraction))
        assertEquals(1f, windSpeed(w.WindBrakeStart))
        for (i in 0..40) {
            val f = 0.25f + (w.WindBrakeStart - 0.25f) * i / 40
            assertEquals(1f, windSpeed(f), "cruise at $f")
        }
        for (i in -50..150) assertTrue(windSpeed(i / 100f) <= 1f)
        assertTrue(close(w.WindBrakeStart, 0.65f, 1e-6f))
    }

    // ── windTapeVelocity ───────────────────────────────────────────────────────────────────────

    @Test
    fun `tape velocity is the play drive at rest and the wind speed at full wind`() {
        assertEquals(g.TapeSpeed, windTapeVelocity(0f, playing = true))
        assertEquals(0f, windTapeVelocity(0f, playing = false))
        for (playing in listOf(true, false)) {
            assertEquals(w.WindTapeSpeed, windTapeVelocity(1f, playing))
            assertEquals(-w.WindTapeSpeed, windTapeVelocity(-1f, playing))
        }
        assertTrue(w.WindTapeSpeed > g.TapeSpeed)
        assertTrue(close(w.WindTapeSpeed / g.TapeSpeed, 3.6f, 1e-3f), "1080°/s against play's 300°/s")
    }

    @Test
    fun `tape velocity never exceeds the wind speed`() {
        for (playing in listOf(true, false)) {
            for (i in -100..100) {
                val v = windTapeVelocity(i / 100f, playing)
                assertTrue(abs(v) <= w.WindTapeSpeed + 1e-3f, "s ${i / 100f} playing $playing → $v")
            }
        }
    }

    @Test
    fun `a rewind while playing brakes through 0 where the play drive and the wind balance`() {
        // (1 − |s|)·P = |s|·W  ⇒  |s| = P / (P + W) = 1 / 4.6 with the 1080°/s cap
        val balance = g.TapeSpeed / (g.TapeSpeed + w.WindTapeSpeed)
        assertTrue(close(balance, 0.217f, 1e-3f), "$balance")
        assertTrue(windTapeVelocity(-(balance - 0.01f), playing = true) > 0f)
        assertTrue(windTapeVelocity(-(balance + 0.01f), playing = true) < 0f)
        // and nowhere else: positive all the way down to the balance, negative beyond it
        for (i in 0..100) {
            val s = i / 100f
            val v = windTapeVelocity(-s, playing = true)
            if (s < balance - 1e-3f) assertTrue(v > 0f, "s −$s → $v")
            if (s > balance + 1e-3f) assertTrue(v < 0f, "s −$s → $v")
        }
    }

    // ── advanceHubAngle ────────────────────────────────────────────────────────────────────────

    /** The play-only form this replaced, verbatim. */
    private fun oldAdvance(angle: Float, packRadius: Float, dtSeconds: Float): Float {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        val next = angle - hubDegreesPerSecond(packRadius) * dt
        return ((next % 360f) + 360f) % 360f
    }

    @Test
    fun `the extended hub step is bit-identical to the old one with its defaults`() {
        for (angle in listOf(0f, 5f, 100f, 123.456f, 359.5f)) {
            for (r in listOf(g.RMin, 150f, g.RMax)) {
                for (dt in listOf(-1f, 0f, 1f / 120f, 1f / 60f, 0.05f, 0.1f, 5f)) {
                    val old = oldAdvance(angle, r, dt)
                    assertEquals(old, advanceHubAngle(angle, r, dt), "$angle $r $dt")
                    assertEquals(old, advanceHubAngle(angle, r, dt, g.TapeSpeed, Float.MAX_VALUE), "$angle $r $dt")
                }
            }
        }
        assertTrue(close(advanceHubAngle(100f, g.RMin, 0.1f, g.TapeSpeed, Float.MAX_VALUE), 70f))
    }

    @Test
    fun `a negative tape velocity turns the hub the other way, zero not at all`() {
        assertTrue(close(advanceHubAngle(100f, g.RMin, 0.05f, -g.TapeSpeed), 115f))   // 300°/s × 0.05 s, rising
        assertEquals(100f, advanceHubAngle(100f, g.RMin, 0.05f, 0f))
        assertTrue(close(advanceHubAngle(355f, g.RMin, 0.05f, -g.TapeSpeed), 10f), "wraps above 360")
    }

    @Test
    fun `one frame's turn is clamped to maxStepDeg`() {
        assertTrue(close(advanceHubAngle(100f, g.RMin, 0.1f, g.TapeSpeed, 20f), 80f))    // 30° clamped to 20°
        assertTrue(close(advanceHubAngle(100f, g.RMin, 0.1f, -g.TapeSpeed, 20f), 120f))
        assertTrue(close(advanceHubAngle(100f, g.RMin, 0.05f, g.TapeSpeed, 20f), 85f), "under the clamp: untouched")
    }

    @Test
    fun `no hub ever turns more than 18 degrees in a 60 Hz frame`() {
        assertTrue(w.WindHubMaxDegPerSec / 60f <= w.HubMaxStepDeg)
        var r = g.RMin
        while (r <= g.RMax) {
            for (i in -100..100) {
                for (playing in listOf(true, false)) {
                    val v = windTapeVelocity(i / 100f, playing)
                    // the velocity cap alone holds it; the clamp is a second line
                    val free = abs(unwrap(advanceHubAngle(180f, r, 1f / 60f, v) - 180f))
                    val clamped = abs(unwrap(advanceHubAngle(180f, r, 1f / 60f, v, w.HubMaxStepDeg) - 180f))
                    assertTrue(free <= 18.001f, "r $r s ${i / 100f} playing $playing → $free°")
                    assertTrue(clamped <= 18.001f)
                }
            }
            r += 1f
        }
    }
}

class CassetteReelTrackerTest {

    private val w = CassetteWind

    @Test
    fun `the first value primes the tracker without a wind`() {
        val t = ReelTracker(0.1f)
        assertEquals(ReelInput.Accept, t.onProgress(0.8f, Track240))
        assertEquals(0.8f, t.displayed)
        assertEquals(0.8f, t.target)
        assertFalse(t.winding)
    }

    @Test
    fun `a tick is taken as it comes`() {
        val t = primed(0.5f)
        val tick = 0.5f + 1_000f / Track240
        assertEquals(ReelInput.Accept, t.onProgress(tick, Track240))
        assertEquals(tick, t.displayed)
        assertEquals(tick, t.target)
        assertFalse(t.winding)
        assertEquals(0f, Clock().frame(t))
    }

    @Test
    fun `a jump winds - stamped on its first frame, then monotone inside its span`() {
        val t = primed(0.2f)
        assertEquals(ReelInput.Wind, t.onProgress(0.8f, Track240))
        assertTrue(t.winding)
        assertEquals(0.2f, t.displayed, "nothing moves at the call")
        assertEquals(0.8f, t.target)
        val clock = Clock()
        assertEquals(0f, clock.frame(t), "the stamping frame")
        assertEquals(0.2f, t.displayed, "the stamp moves nothing")
        var last = t.displayed
        var moved = 0
        while (t.winding) {
            clock.frame(t)
            assertTrue(t.displayed >= last, "monotone: ${t.displayed} < $last")
            assertTrue(t.displayed in 0.2f..0.8f, "inside the span: ${t.displayed}")
            if (t.displayed > last) moved++
            last = t.displayed
        }
        assertTrue(moved > 20, "a real wind, not a snap ($moved moving frames)")
    }

    @Test
    fun `the wind speed is positive for a fast-forward and negative for a rewind`() {
        val ff = primed(0.2f).apply { onProgress(0.8f, Track240) }
        val rw = primed(0.8f).apply { onProgress(0.2f, Track240) }
        val clock = Clock()
        clock.frame(ff); clock.frame(rw)                       // the stamps
        repeat(20) {
            val sf = ff.onFrame(clock.now)
            val sr = rw.onFrame(clock.now)
            clock.now += Frame60
            assertTrue(sf > 0f, "fast-forward s $sf")
            assertTrue(sr < 0f, "rewind s $sr")
        }
    }

    @Test
    fun `a wind ends exactly on its target within its duration`() {
        val t = primed(0.2f).apply { onProgress(0.8f, Track240) }
        val maxFrames = ceil(windDurationMs(0.6f) / 16.67).toInt() + 2
        val clock = Clock()
        var n = 0
        var s = 1f
        while (t.winding) {
            s = clock.frame(t)
            n++
            assertTrue(n <= maxFrames, "still winding after $n frames")
        }
        assertEquals(0.8f, t.displayed)
        assertFalse(t.winding)
        assertEquals(0f, s, "the completing frame returns 0")
        assertEquals(0f, clock.frame(t), "idle afterwards")
    }

    @Test
    fun `a small correction during a wind moves its end but keeps its timeline`() {
        val reference = Clock().toEnd(primed(0.2f).apply { onProgress(0.8f, Track240) })
        val t = primed(0.2f).apply { onProgress(0.8f, Track240) }
        val clock = Clock()
        repeat(10) { clock.frame(t) }
        val corrected = 0.8f + 2_000f / Track240
        assertEquals(ReelInput.Accept, t.onProgress(corrected, Track240))
        assertTrue(t.winding)
        assertEquals(corrected, t.target)
        assertNotEquals(0f, clock.frame(t), "no re-stamp: the timeline runs on")
        val total = 11 + clock.toEnd(t)
        assertEquals(reference, total, "ends at the original T")
        assertEquals(corrected, t.displayed, "on the corrected value")
    }

    @Test
    fun `a tick inside a rewind is folded into the remaining distance - the packs never step forward`() {
        // Re-measurement 2026-09-26 (repeat-one at a quarter speed): a sub-threshold value accepted
        // mid-wind used to move the end outright, shifting the packs by δ·ease(f) at once — against
        // a rewind that is a step the WRONG way. The span is re-based instead: the position shown
        // at that instant is unchanged and the remaining ease carries the tick.
        val t = primed(0.9f)
        val wrapped = 1_500f / Track240
        assertEquals(ReelInput.Wind, t.onProgress(wrapped, Track240))
        val clock = Clock()
        clock.frame(t)                                        // the stamp
        repeat(26) { clock.frame(t) }                         // about half the ~870 ms wind
        val before = t.displayed
        val ticked = wrapped + 1_000f / Track240              // the one tick a ≤ 900 ms wind can see
        assertEquals(ReelInput.Accept, t.onProgress(ticked, Track240))
        assertEquals(before, t.displayed, "the tick moves nothing at the call")
        assertEquals(ticked, t.target)
        assertTrue(t.winding)
        assertTrue(clock.frame(t) < 0f, "still a rewind after the re-base")
        assertTrue(t.displayed < before, "and still moving the rewind's way")
        var last = t.displayed
        while (t.winding) {
            clock.frame(t)
            assertTrue(t.displayed <= last + 1e-6f, "never forward: ${t.displayed} > $last")
            last = t.displayed
        }
        assertEquals(ticked, t.displayed, "ends exactly on the ticked value")
    }

    @Test
    fun `a correction in a wind's last frames still lands it exactly on the corrected value`() {
        // Past the re-base limit (98 % of the ease) the end simply moves: the difference lands in
        // the last frame or two, on the corrected value, never on a NaN.
        val t = primed(0.2f)
        t.onProgress(0.8f, Track240)
        val clock = Clock()
        clock.frame(t)
        while (t.winding && t.displayed < 0.2f + 0.6f * 0.985f) clock.frame(t)
        assertTrue(t.winding, "still winding in the brake tail")
        val corrected = 0.8f + 1_000f / Track240
        assertEquals(ReelInput.Accept, t.onProgress(corrected, Track240))
        val n = clock.toEnd(t)
        assertTrue(n in 1..8, "a few frames left ($n)")
        assertFalse(t.displayed.isNaN())
        assertEquals(corrected, t.displayed)
    }

    @Test
    fun `a jump away from the displayed position retargets from where the packs are`() {
        val t = primed(0.2f).apply { onProgress(0.8f, Track240) }
        val clock = Clock()
        var running = 0f
        repeat(25) { running = clock.frame(t) }
        val before = t.displayed
        assertTrue(before > 0.3f && before < 0.7f, "mid-wind: $before")
        assertTrue(running > 0f, "fast-forwarding at speed: $running")
        assertEquals(ReelInput.Wind, t.onProgress(0.05f, Track240))
        assertEquals(before, t.displayed, "position continuous at the call")
        assertEquals(0.05f, t.target)
        assertEquals(running, clock.frame(t), "the next frame stamps the new wind, at the old speed")
        assertEquals(before, t.displayed, "the stamp moves no pack")
        clock.frame(t); clock.frame(t)
        assertTrue(t.displayed < before, "heading for 0.05")
        clock.toEnd(t)
        assertEquals(0.05f, t.displayed)
    }

    @Test
    fun `a jump landing near the displayed position retargets too - the stamp moves nothing`() {
        // Review 2026-09-26: a 240 s seek 0.9 → 0.1 is 24 frames in, the packs near 0.5, when a
        // value lands at 0.504 — a jump from the target but not from the packs (a new item resuming
        // there, read by the OUTGOING face, say). Folded into the running wind it moved the packs
        // ~0.21 of the tape in one frame, the outgoing face's last.
        val t = primed(0.9f).apply { onProgress(0.1f, Track240) }
        val clock = Clock()
        var running = clock.frame(t)                          // the stamp
        repeat(24) { running = clock.frame(t) }
        val before = t.displayed
        assertTrue(close(before, 0.5f), "mid-wind: $before")
        assertTrue(running < 0f, "rewinding at speed: $running")
        val near = 0.504f
        assertTrue(isReelJump(t.target, near, Track240))
        assertFalse(isReelJump(before, near, Track240))
        assertEquals(ReelInput.Wind, t.onProgress(near, Track240))
        assertEquals(near, t.target)
        assertEquals(before, t.displayed, "nothing moves at the call")
        assertEquals(running, clock.frame(t), "a stamp, at the replaced wind's speed")
        assertEquals(before, t.displayed, "the stamp moves no pack: an outgoing face stays put")
        clock.toEnd(t)
        assertEquals(near, t.displayed, "the new wind ends exactly on it")
    }

    @Test
    fun `a retarget's stamp never steps a rewinding hub the play way`() {
        // Review 2026-09-26: the stamp returned 0, so while playing the hubs fell onto the play
        // drive for that frame — one step anticlockwise against a clockwise rewind, shown by the
        // outgoing face of a skip made during a wind
        val t = primed(0.9f).apply { onProgress(0.2f, Track240) }
        val clock = Clock()
        var running = 0f
        repeat(16) { running = clock.frame(t) }              // the stamp + 15 frames: at cruise
        assertEquals(-1f, running, "a rewind at cruise")
        assertEquals(ReelInput.Wind, t.onProgress(0.1f, Track240))
        val stamp = clock.frame(t)
        assertEquals(running, stamp)
        val v = windTapeVelocity(stamp, playing = true)
        assertTrue(v < 0f, "still rewinding on the stamp: $v")
        val r = packRadii(t.displayed)
        val step = unwrap(advanceHubAngle(100f, r.supply, 1f / 60f, v, w.HubMaxStepDeg) - 100f)
        assertTrue(step > 0f, "the supply hub keeps turning clockwise: $step")
    }

    @Test
    fun `two retargets between frames still pass the running speed on`() {
        val t = primed(0.9f).apply { onProgress(0.2f, Track240) }
        val clock = Clock()
        var running = 0f
        repeat(16) { running = clock.frame(t) }
        assertEquals(ReelInput.Wind, t.onProgress(0.05f, Track240))   // a retarget, not yet stamped…
        assertEquals(ReelInput.Wind, t.onProgress(0.6f, Track240))    // …replaced before any frame
        assertEquals(running, clock.frame(t), "the unstamped retarget passed the speed on")
        clock.toEnd(t)
        assertEquals(0.6f, t.displayed)
    }

    @Test
    fun `a fresh wind stamps 0 - after a finished wind, and after an unknown duration`() {
        val t = primed(0.2f).apply { onProgress(0.8f, Track240) }
        val clock = Clock()
        clock.toEnd(t)
        assertEquals(ReelInput.Wind, t.onProgress(0.3f, Track240))
        assertEquals(0f, clock.frame(t), "nothing carried over from a finished wind")
        repeat(20) { clock.frame(t) }
        assertTrue(t.winding, "mid-wind")
        assertEquals(ReelInput.Accept, t.onProgress(0.6f, 0L))        // snaps, ends the wind, un-primes
        assertFalse(t.winding)
        assertEquals(ReelInput.Accept, t.onProgress(0.6f, Track240))  // primes again
        assertEquals(ReelInput.Wind, t.onProgress(0.1f, Track240))
        assertEquals(0f, clock.frame(t), "nothing carried over across an un-prime")
    }

    @Test
    fun `an exact 0 is held, and the next value resolves it`() {
        val t = primed(0.5f)
        assertEquals(ReelInput.Hold, t.onProgress(0f, Track240))
        assertTrue(t.holding)
        assertFalse(t.winding)
        assertEquals(0.5f, t.displayed)
        assertEquals(0.5f, t.target)
        val clock = Clock()
        repeat(5) { assertEquals(0f, clock.frame(t)) }
        assertEquals(0.5f, t.displayed, "nothing moves while holding")
        // a stale poll restoring the old position: no jump against the untouched target, no wind
        assertEquals(ReelInput.Accept, t.onProgress(0.504f, Track240))
        assertFalse(t.holding)
        assertFalse(t.winding)
        assertEquals(0.504f, t.displayed)
        // …or a restart: it winds from the pre-zero position
        val r = primed(0.5f).apply { onProgress(0f, Track240) }
        assertEquals(ReelInput.Wind, r.onProgress(0.004f, Track240))
        assertFalse(r.holding)
        assertTrue(r.winding)
        assertEquals(0.5f, r.displayed)
        clock.frame(r); clock.frame(r); clock.frame(r)
        assertTrue(r.displayed < 0.5f, "rewinding from 0.5: ${r.displayed}")
    }

    @Test
    fun `a hold with no value after it winds to 0, and expiring nothing is a no-op`() {
        val t = primed(0.5f).apply { onProgress(0f, Track240) }
        t.onHoldExpired(Track240)
        assertFalse(t.holding)
        assertTrue(t.winding)
        assertEquals(0f, t.target)
        assertEquals(0.5f, t.displayed)
        Clock().toEnd(t)
        assertEquals(0f, t.displayed)
        val idle = primed(0.5f)
        idle.onHoldExpired(Track240)
        assertFalse(idle.winding)
        assertEquals(0.5f, idle.displayed)
    }

    @Test
    fun `an exact 0 within the threshold of the target is simply taken`() {
        val t = primed(0.01f)                                 // 2.4 s into a four-minute song
        assertEquals(ReelInput.Accept, t.onProgress(0f, Track240))
        assertFalse(t.holding)
        assertFalse(t.winding)
        assertEquals(0f, t.displayed)
    }

    @Test
    fun `an unknown duration snaps and un-primes, the next known one primes again`() {
        val t = primed(0.5f)
        assertEquals(ReelInput.Accept, t.onProgress(0.9f, 0L))
        assertEquals(0.9f, t.displayed)
        assertFalse(t.winding)
        assertEquals(ReelInput.Accept, t.onProgress(0.1f, Track240), "primes, however far away")
        assertEquals(0.1f, t.displayed)
        assertFalse(t.winding)
        assertEquals(ReelInput.Wind, t.onProgress(0.6f, Track240), "primed again: a jump winds")
        // an unknown duration during a wind snaps and ends it
        assertEquals(ReelInput.Accept, t.onProgress(0.3f, 0L))
        assertFalse(t.winding)
        assertEquals(0.3f, t.displayed)
    }

    @Test
    fun `a frozen wind holds its fraction and never completes`() {
        val t = primed(0.9f).apply { onProgress(0.2f, Track240) }
        t.onFrame(0L, freezeFraction = 0.5f)                  // the stamp
        repeat(200) { i -> t.onFrame((i + 1) * Frame60, freezeFraction = 0.5f) }
        assertEquals(0.9f + (0.2f - 0.9f) * windEase(0.5f), t.displayed)
        assertTrue(t.winding)
        assertTrue(close(t.displayed, 0.525f, 1e-4f), "${t.displayed}")
    }

    @Test
    fun `a quarter time scale takes four times the frames`() {
        val n1 = Clock().toEnd(primed(0.2f).apply { onProgress(0.7f, Track240) }, timeScale = 1f)
        val n4 = Clock().toEnd(primed(0.2f).apply { onProgress(0.7f, Track240) }, timeScale = 0.25f)
        // the stamping frame is not scaled: compare the moving frames
        assertTrue(abs((n4 - 1) - 4 * (n1 - 1)) <= 4, "$n1 frames → $n4 at a quarter")
    }

    @Test
    fun `a repeat-one wind rewinds the whole tape smoothly, inside the aliasing limit`() {
        val from = 0.999f
        val to = 1_500f / Track240                            // 0.00625: the poll after a wrap
        val tSeconds = windDurationMs(to - from) / 1000f
        val maxPackStep = w.WindPeakSpeed * abs(to - from) / tSeconds * (1f / 60f) * 1.01f
        for (playing in listOf(false, true)) {
            val t = primed(from)
            assertEquals(ReelInput.Wind, t.onProgress(to, Track240))
            val clock = Clock()
            var last = -1L
            var supply = 0f
            var takeUp = 0f
            var turned = 0f
            var shown = t.displayed
            val signs = ArrayList<Int>()
            while (t.winding) {
                val now = clock.now
                val s = clock.frame(t)
                assertTrue(abs(t.displayed - shown) <= maxPackStep, "pack step ${abs(t.displayed - shown)}")
                shown = t.displayed
                if (last >= 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    val v = windTapeVelocity(s, playing)
                    val r = packRadii(t.displayed)
                    val nextSupply = advanceHubAngle(supply, r.supply, dt, v, w.HubMaxStepDeg)
                    val nextTakeUp = advanceHubAngle(takeUp, r.takeUp, dt, v, w.HubMaxStepDeg)
                    val ds = unwrap(nextSupply - supply)
                    val dk = unwrap(nextTakeUp - takeUp)
                    assertTrue(abs(ds) <= 18.001f && abs(dk) <= 18.001f, "hub steps $ds / $dk")
                    if (abs(ds) >= 1f) {
                        assertTrue(abs(ds / dk - r.takeUp / r.supply) <= 1e-3f, "ω ∝ 1/r: ${ds / dk} vs ${r.takeUp / r.supply}")
                    }
                    if (!playing) assertTrue(ds >= -1e-3f, "paused: a rewind only ever turns clockwise ($ds)")
                    if (abs(ds) >= 1e-3f) {
                        val sign = if (ds > 0f) 1 else -1
                        if (signs.isEmpty() || signs.last() != sign) signs += sign
                    }
                    turned += ds
                    supply = nextSupply
                    takeUp = nextTakeUp
                }
                last = now
            }
            assertEquals(to, t.displayed, "ends exactly on the target")
            if (playing) {
                assertEquals(listOf(-1, 1, -1), signs, "play → brake through 0 → rewind → brake → play")
            } else {
                assertTrue(turned > 0f, "paused: the unwrapped angle rises ($turned)")
                assertEquals(listOf(1), signs)
            }
        }
    }
}

class CassetteReelItemHoldTest {

    @Test
    fun `the position passes while there is an item and is held while there is none`() {
        val hold = ReelItemHold()
        assertEquals(0.5f, hold.progress(hasItem = true, progress = 0.5f))
        assertEquals(0.5f, hold.progress(hasItem = false, progress = 0f), "the null item's 0")
        assertEquals(0.5f, hold.progress(hasItem = false, progress = 1_000f / Track240), "the tick counting on from it")
        assertEquals(0.5125f, hold.progress(hasItem = true, progress = 0.5125f), "the item is back")
        assertEquals(0.5125f, hold.progress(hasItem = false, progress = 0f), "held again from there")
    }

    @Test
    fun `the first value passes even without an item - there is nothing to hold yet`() {
        val hold = ReelItemHold()
        assertEquals(0.3f, hold.progress(hasItem = false, progress = 0.3f))
        assertEquals(0.3f, hold.progress(hasItem = false, progress = 0f))
    }

    @Test
    fun `a null-item poll while playing never winds the reels`() {
        // Review 2026-09-26, the verifier's sequence on a 240 s song at 0.5: a 200 with item null
        // zeroes the position and keeps the duration, and while playing the 1 s tick counts on
        // from that 0. Straight into the tracker the 0 was held, but the first tick wound the whole
        // tape back and the next poll wound it forward again.
        val hold = ReelItemHold()
        val t = ReelTracker(hold.progress(hasItem = true, progress = 0.5f))
        val clock = Clock()
        val back = 0.5f + 3_000f / Track240                   // the next poll, 3 s on
        val feed = listOf(
            true to 0.5f,                                     // the face's first value: primes
            false to 0f,                                      // the null-item poll
            false to 1_000f / Track240,                       // the tick, within a second
            false to 2_000f / Track240,
            true to back,                                     // the item is back
        )
        for ((hasItem, raw) in feed) {
            assertEquals(ReelInput.Accept, t.onProgress(hold.progress(hasItem, raw), Track240), "($hasItem, $raw)")
            repeat(10) { clock.frame(t) }
            assertFalse(t.winding, "($hasItem, $raw)")
            assertTrue(t.displayed >= 0.5f, "never wound back: ${t.displayed}")
        }
        assertEquals(back, t.displayed, "the item's return is a correction, taken as it comes")
    }
}
