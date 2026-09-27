package com.crsmthw.lyra.ui.cassette

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * THE REEL WIND (2026-09-26): what the reels do when the playback position JUMPS — a seek made
 * elsewhere (another Spotify client; a seek reaches the cassette only through the 3 s poll, since
 * the notification has no seek action and the in-app seek bar is under the overlay), or a
 * repeat-one wrap from the end back to the start. The packs used to snap to the new radii; they
 * now rewind / fast-forward to them the way a deck does, and the hubs spin with them. Pure Kotlin
 * — no Android, no Compose — so all of it runs in JVM tests (CassetteReelWindTest). One
 * [ReelTracker] per cassette face (Cassette.kt's `ReelSpin`); design record docs/CASSETTE.md.
 *
 * ── Jump detection ([ReelTracker.onProgress], fed by the face's snapshotFlow) ────────────────
 *   a change from → to is a JUMP when the duration is known and it is at least
 *   [CassetteTiming.WindJumpMinMs] of track time AND [CassetteWind.WindJumpMinFraction] of the
 *   tape; `from` is the last ACCEPTED value (the tracker's target), never the displayed one
 *   tick / correction      accepted: snapped while idle, as always; during a wind it moves the
 *                          wind's END without moving the packs — the span is RE-BASED so the
 *                          position shown at that instant is unchanged and the remaining ease
 *                          carries the difference (the one tick that can land inside a ≤ 900 ms
 *                          wind used to shift the packs by δ·ease(f) at once: ~1 px on a 3-minute
 *                          song, a visible hitch against a rewind on a short track)
 *   first value            primes the tracker: nothing winds on a new face, the overlay's entry,
 *                          or when the duration arrives; a value with duration ≤ 0 un-primes it
 *   exact 0f               a HOLD, not a jump: Lyra's own optimistic reset on a skip, the wake
 *                          fallback. The next value resolves it (a stale poll that restores the
 *                          old position is no jump; a restart winds from the pre-zero position);
 *                          with no value within [CassetteTiming.WindZeroHoldMs] it winds to 0. A
 *                          null-item poll's 0 never gets here: PlayerScreen holds the position
 *                          while the item is absent ([ReelItemHold])
 *   a jump while winding   ALWAYS a new wind from the DISPLAYED value, a retarget: the position
 *                          stays continuous, the stamping frame keeps the hubs on the replaced
 *                          wind's speed, then the speed restarts from 0. Never folded into the
 *                          running wind — moving a stamped wind's end by a jump shifts the packs
 *                          in one frame (see [ReelTracker.windTo])
 * ── The wind ───────────────────────────────────────────────────────────────────────────────
 *   displayed = from + (to − from)·windEase(f), f = elapsed × timeScale / T, stamped on its FIRST
 *   frame (which moves no pack). T = [windDurationMs] = 350 + 550·√|Δp| ms — by PACK distance,
 *   since every track maps onto the same tape (0.01 → 405, 0.25 → 625, a whole-tape wrap → 900).
 *   The velocity is a trapezoid, the way a deck motor spins up ([CassetteWind.WindRampFraction]),
 *   cruises and brakes ([CassetteWind.WindBrakeFraction]); windEase is its integral, C¹ and
 *   monotone. The packs follow packRadii(displayed) exactly, the pack peek with them; a whole-tape
 *   wind peaks at 119 × 1.43 / 0.9 ≈ 189 units/s, ~3 units per 60 Hz frame.
 * ── The hubs ───────────────────────────────────────────────────────────────────────────────
 *   ONE signed tape velocity for both ([windTapeVelocity]), positive = the play direction (both
 *   anticlockwise): v = (1 − |s|)·P + s·W, s = ±windSpeed(f) (+ fast-forward, − rewind), P = the
 *   play speed while playing else 0, W = [CassetteWind.WindTapeSpeed]. The play drive fades out
 *   as the wind takes over (the pinch roller lifts): a rewind while playing brakes through 0 at
 *   |s| = P/(P+W) ≈ 0.22, cruises clockwise, brakes, reverses and resumes play; a fast-forward
 *   rises to W; paused it is 0 → ±W → 0. ω = v / r per hub, so the emptier hub turns faster. W is
 *   set so the EMPTY hub peaks at [CassetteWind.WindHubMaxDegPerSec] = 18° per 60 Hz frame: under
 *   a tooth's ~17–20° width and well under the 30° at which the hub's 60°-periodic art aliases,
 *   so the direction always reads. A real deck winds 10–30× play speed and would alias — the hubs
 *   carry direction and effort, the packs carry distance, on one shared velocity curve.
 */

/** The wind's tuning constants, in the painter's units (see the header above). */
internal object CassetteWind {
    /**
     * The smallest position change, as a fraction of the tape, that winds: 1 % ≈ 1.2 units, about
     * 2.3 px on the cover screen — a smaller wind could not be seen moving. On a long track this
     * floor decides (10 s of a one-hour episode snaps); [CassetteTiming.WindJumpMinMs] decides on
     * an ordinary song.
     */
    const val WindJumpMinFraction = 0.01f

    /** The share of a wind's timeline spent spinning up to cruise (the trapezoid's rising edge). */
    const val WindRampFraction = 0.25f

    /** The share spent braking to a stop — longer than the ramp, so the packs settle onto the new
     *  position rather than stopping dead on it. */
    const val WindBrakeFraction = 0.35f

    /** Where the brake starts on the timeline, `1 − WindBrakeFraction`: ONE constant that
     *  [windSpeed] and [windEase] share, so their plateau ends agree to the bit. */
    const val WindBrakeStart = 1f - WindBrakeFraction

    /** The trapezoid's cruise speed, normalised so a wind covers exactly its whole distance:
     *  `1 / (1 − ramp/2 − brake/2)` ≈ 1.4286 (the area under the trapezoid is then 1). */
    const val WindPeakSpeed = 1f / (1f - WindRampFraction / 2f - WindBrakeFraction / 2f)

    /**
     * The EMPTY hub's peak wind speed, degrees per second: 18° per 60 Hz frame (9° at 120 Hz),
     * under a tooth's ~17–20° width and well under the 30° at which the 60°-periodic hub art
     * aliases, so consecutive frames overlap and the direction is never ambiguous. The one tuning
     * knob of the hubs' wind; never above [HubMaxStepDeg] × 60 = 1200.
     */
    const val WindHubMaxDegPerSec = 1080f

    /** The tape speed that turns the empty hub (r = [CassetteGeometry.RMin]) at
     *  [WindHubMaxDegPerSec]: ≈ 1923 units/s, 3.6 × [CassetteGeometry.TapeSpeed]. The full hub then
     *  peaks at ≈ 498°/s. */
    const val WindTapeSpeed = (WindHubMaxDegPerSec * PI / 180.0 * CassetteGeometry.RMin).toFloat()

    /** Every hub step (play or wind) is clamped to a third of the art's 60° period, so a hitch or
     *  a slow frame never turns the teeth far enough to read backwards. Play alone only reaches it
     *  on a frame longer than ~67 ms. */
    const val HubMaxStepDeg = 20f
}

/**
 * Is `from → to` a JUMP the reels should wind to, rather than a tick or a poll's correction to
 * take as it comes? Only with a known duration, and only when the change is BOTH at least
 * [CassetteTiming.WindJumpMinMs] of track time and [CassetteWind.WindJumpMinFraction] of the
 * tape: the time rule keeps the ticks and the up to ~4.5 s correction a poll makes after a pause
 * or stall elsewhere from winding; the fraction rule drops a long track's winds too small to see.
 * NaN is never a jump.
 */
internal fun isReelJump(from: Float, to: Float, durationMs: Long): Boolean {
    if (durationMs <= 0L || from.isNaN() || to.isNaN()) return false
    val d = abs(to - from)
    return d >= CassetteWind.WindJumpMinFraction && d * durationMs >= CassetteTiming.WindJumpMinMs
}

/**
 * How long a wind across `deltaP` of the tape takes, in ms: [CassetteTiming.WindMinMs] +
 * (WindMaxMs − WindMinMs)·√|Δp| — sub-linear, so a short hop still reads as a wind and a whole
 * tape (a repeat-one wrap) stays under a second: 0.01 → 405, 0.25 → 625, 1.0 → 900. Driven by
 * PACK distance, not track time, because every track maps onto the same tape. |Δp| is capped at 1;
 * NaN reads as the shortest wind.
 */
internal fun windDurationMs(deltaP: Float): Int {
    if (deltaP.isNaN()) return CassetteTiming.WindMinMs
    val span = CassetteTiming.WindMaxMs - CassetteTiming.WindMinMs
    return (CassetteTiming.WindMinMs + span * sqrt(abs(deltaP).coerceAtMost(1f))).roundToInt()
}

/**
 * The wind's normalised speed at timeline fraction `f`: a trapezoid, the way a deck motor spins
 * up ([CassetteWind.WindRampFraction]), cruises at 1 and brakes ([CassetteWind.WindBrakeFraction]).
 * Exactly 0 at and outside the ends (f ≤ 0, f ≥ 1, NaN), where the hubs are back on the play drive.
 */
internal fun windSpeed(f: Float): Float = when {
    f.isNaN() || f <= 0f || f >= 1f   -> 0f
    f < CassetteWind.WindRampFraction -> f / CassetteWind.WindRampFraction
    f <= CassetteWind.WindBrakeStart  -> 1f
    else                              -> (1f - f) / CassetteWind.WindBrakeFraction
}

/**
 * How far along its span a wind is at timeline fraction `f`: the integral of [windSpeed], scaled
 * by [CassetteWind.WindPeakSpeed] so it ends exactly on 1 — quadratic in over the ramp, linear
 * through the cruise, quadratic out over the brake. C¹ and monotone; 0 at f ≤ 0 (and NaN), 1 at
 * f ≥ 1. Never a spring: a progress-like value must not overshoot.
 */
internal fun windEase(f: Float): Float {
    val a = CassetteWind.WindRampFraction
    val b = CassetteWind.WindBrakeFraction
    val v = CassetteWind.WindPeakSpeed
    return when {
        f.isNaN() || f <= 0f            -> 0f
        f >= 1f                         -> 1f
        f < a                           -> v * f * f / (2f * a)
        f <= CassetteWind.WindBrakeStart -> v * (f - a / 2f)
        else                            -> { val g = 1f - f; 1f - v * g * g / (2f * b) }
    }
}

/**
 * The hubs' ONE signed tape velocity, units/s (positive = the play direction, both hubs
 * anticlockwise), for the signed wind speed `s` (+ fast-forward, − rewind, 0 = no wind):
 * `(1 − |s|)·P + s·W`, with P = [CassetteGeometry.TapeSpeed] while playing (0 paused) and
 * W = [CassetteWind.WindTapeSpeed]. The play drive fades out as the wind takes over — the pinch
 * roller lifts — so |v| ≤ W always, and a rewind while playing passes smoothly through 0.
 * `s` is clamped to −1..1; NaN reads as no wind.
 */
internal fun windTapeVelocity(s: Float, playing: Boolean): Float {
    val w = if (s.isNaN()) 0f else s.coerceIn(-1f, 1f)
    val play = if (playing) CassetteGeometry.TapeSpeed else 0f
    return (1f - abs(w)) * play + w * CassetteWind.WindTapeSpeed
}

/** How [ReelTracker.onProgress] classified a value. Only [Hold] changes what the caller does next
 *  (it starts the [CassetteTiming.WindZeroHoldMs] timeout). */
internal enum class ReelInput {
    /** Taken as the position without a new wind: snapped while idle, or moved the running wind's
     *  end. Also every priming value, and any value with an unknown duration. */
    Accept,

    /** A jump: a new wind from the DISPLAYED position, stamped on the next frame — a retarget when
     *  one was already running, never folded into it. */
    Wind,

    /** An exact-0 jump: held until the next value, or until [ReelTracker.onHoldExpired]. */
    Hold,
}

/**
 * One cassette face's reel position: the pure state machine behind the reel wind (see the header).
 * [onProgress] classifies every value the face's snapshotFlow reads, [onFrame] advances a running
 * wind once per frame, [onHoldExpired] ends an exact-0 hold, and [displayed] is what the packs
 * show. Main-thread only: the face's collector and its frame loop share it, never concurrently.
 *
 * Invariant: no wind ⇒ [displayed] == [target]; a wind ⇒ its end == [target].
 */
internal class ReelTracker(initial: Float) {

    /**
     * A running wind. `to` moves with every accepted value; the timeline starts on the first
     * [onFrame] after it is created (`startNanos` < 0 until then). `stampSpeed` is what that
     * stamping frame returns: 0 for a fresh wind, the replaced wind's last speed for a retarget
     * (see [onFrame]). `lastSpeed` is what the span last returned — its stamp speed until it has
     * moved, so a retarget replacing a still-unstamped retarget passes the running speed on — and
     * it seeds the next retarget's stamp speed.
     */
    private class Span(var from: Float, var to: Float, val durationMs: Int, val stampSpeed: Float) {
        var startNanos = -1L
        var lastSpeed = stampSpeed
        /** The ease the span last moved to (0 until it has), the anchor of [moveEnd]'s re-base. */
        var lastEase = 0f

        /**
         * Moves the end to `p` WITHOUT moving the packs, which show `displayed` at [lastEase]: the
         * start is re-based so `from + (p − from)·lastEase == displayed` still holds, and the
         * remaining ease then carries the packs from where they are to `p` — monotone, in the
         * direction of `p − displayed` (which [onFrame]'s sign follows). Past [RebaseEaseLimit]
         * almost no ease is left to carry anything, so the end simply moves: the difference lands
         * within the last frame or two — a tick's ~0.7 units on a 3-minute song, in the brake
         * tail where the packs are all but stopped.
         */
        fun moveEnd(p: Float, displayed: Float) {
            val e = lastEase
            if (e < RebaseEaseLimit) from = (displayed - p * e) / (1f - e)
            to = p
        }
    }

    /** The position the packs show, 0..1. */
    var displayed = sanitize(initial)
        private set

    /** The last ACCEPTED position — the reference a new value is classified against. */
    var target = displayed
        private set

    /** An exact-0 jump is being held (see [onProgress]). */
    var holding = false
        private set

    /** A wind is running, or waiting for its stamping frame. */
    val winding: Boolean get() = wind != null

    private var primed = false
    private var wind: Span? = null

    /**
     * Classifies a new playback value `raw` (0..1) for a track of `durationMs` — see the header's
     * jump rules. The first value with a known duration only primes (no wind); a value with an
     * unknown duration snaps and un-primes. Any new value ends a hold.
     */
    fun onProgress(raw: Float, durationMs: Long): ReelInput {
        val p = sanitize(raw)
        holding = false
        if (durationMs <= 0L || !primed) {
            primed = durationMs > 0L
            wind = null
            displayed = p
            target = p
            return ReelInput.Accept
        }
        if (!isReelJump(target, p, durationMs)) {
            accept(p)
            return ReelInput.Accept
        }
        if (p == 0f) {
            holding = true
            return ReelInput.Hold
        }
        windTo(p)
        return ReelInput.Wind
    }

    /**
     * A frame the hub clock HELD ([LayoutHold], a relayout the display is frozen across): a running,
     * stamped wind's timeline stands still for `elapsedNanos` too, so its packs do not skip at the
     * cut either. An unstamped wind is untouched (it stamps on the next advancing frame).
     */
    fun holdFrame(elapsedNanos: Long) {
        val w = wind ?: return
        if (w.startNanos >= 0L && elapsedNanos > 0L) w.startNanos += elapsedNanos
    }

    /** No value followed an exact-0 hold within [CassetteTiming.WindZeroHoldMs]: the 0 was real —
     *  wind to it (or take it, when it is no jump from the target). A no-op when not holding. */
    fun onHoldExpired(durationMs: Long) {
        if (!holding) return
        holding = false
        if (isReelJump(target, 0f, durationMs)) windTo(0f) else accept(0f)
    }

    /**
     * Advances a running wind to frame time `frameNanos` and returns `s`, the signed normalised
     * wind speed for [windTapeVelocity] (+ fast-forward, − rewind). 0 when idle and on the frame
     * that completes a wind (which lands exactly on the target).
     *
     * The frame that STAMPS a new wind moves no pack — a wind created between frames never jumps —
     * and returns the span's stamp speed: 0 for a fresh wind, and for a RETARGET the replaced
     * wind's last speed, so the hubs hold their old speed for that one frame. Returning 0 there
     * dropped them onto the play drive, which against a running rewind is one step the PLAY way —
     * and an outgoing face (its collector can see the new track's position a frame before the face
     * is frozen) showed exactly that frame. `timeScale` slows the wind's clock and
     * `freezeFraction` holds it at a fraction of its timeline, never completing — both for the
     * debug preview only.
     */
    fun onFrame(frameNanos: Long, timeScale: Float = 1f, freezeFraction: Float? = null): Float {
        val w = wind ?: return 0f
        if (w.startNanos < 0L) {
            w.startNanos = frameNanos
            return w.stampSpeed
        }
        val f = freezeFraction?.coerceIn(0f, 1f)
            ?: ((frameNanos - w.startNanos) / 1_000_000f * timeScale / w.durationMs).coerceIn(0f, 1f)
        if (f >= 1f) {
            displayed = w.to
            wind = null
            return 0f
        }
        val e = windEase(f)
        displayed = w.from + (w.to - w.from) * e
        w.lastEase = e
        val s = sign(w.to - w.from) * windSpeed(f)
        w.lastSpeed = s
        return s
    }

    /** Takes `p` as the position: at once while idle; as the running wind's new end otherwise,
     *  re-based so the packs do not move at that instant ([Span.moveEnd]). */
    private fun accept(p: Float) {
        target = p
        val w = wind
        if (w != null) w.moveEnd(p, displayed) else displayed = p
    }

    /**
     * Winds to `p` from the DISPLAYED position. While a wind runs this is a RETARGET — always, even
     * when `p` lands close to where the packs are: a new span from the displayed position (so the
     * position stays continuous), unstamped until the next frame, whose stamp moves no pack and
     * carries the replaced wind's last speed. Never folded into the running wind: moving a STAMPED
     * wind's end shifts the packs at once by the move × windEase(f) — for a jump landing near the
     * packs, up to about a quarter of the span — and on an outgoing face, whose collector can see
     * the new track's position before the face is frozen, that shifted frame was its last.
     */
    private fun windTo(p: Float) {
        target = p
        wind = Span(displayed, p, windDurationMs(p - displayed), stampSpeed = wind?.lastSpeed ?: 0f)
    }

    private companion object {
        /** The ease past which [Span.moveEnd] no longer re-bases: `1 − ease` is the share of the
         *  wind left to carry the difference, and below 2 % it is a frame or two — and a division
         *  by nearly nothing. */
        const val RebaseEaseLimit = 0.98f

        /** A NaN or out-of-range position reads as the nearest end (NaN as the start), like
         *  [packRadii]. */
        fun sanitize(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)
    }
}

/**
 * The reels' position while the now-playing ITEM may be briefly absent — what PlayerScreen hands
 * the cassette as `progress`. A poll answering 200 with `item: null` (a transfer started from
 * another Spotify client, an unsupported item) zeroes the position but keeps the duration, and
 * while playing the 1 s progress tick counts on from that 0. Fed straight through, the 0 is held
 * (the tracker's exact-0 hold) but the tick right after it reads as a restart — the whole tape
 * winds back — and the next poll's restored position winds it forward again. So while the item is
 * absent this hands out the last position read WITH one, which the tracker sees as no change at
 * all; when the item returns, its position is only the few seconds played meanwhile past the held
 * one — a correction, taken as it comes (and should the item stay away, the cassette exits).
 * Callers pass "is there an item", never "is there an id": a local file's id is null for the whole
 * track. Plain Kotlin, not snapshot state: [progress] is called where the item and the position
 * are read together — the reels' snapshotFlow, and a new face's first composition.
 */
internal class ReelItemHold {
    /** The last position read with an item; NaN until the first value. */
    private var last = Float.NaN

    /** `progress` itself while there is an item (remembered); the last remembered one while there
     *  is not. The very first value passes whatever it is — there is nothing to hold yet. */
    fun progress(hasItem: Boolean, progress: Float): Float {
        if (hasItem || last.isNaN()) last = progress
        return last
    }
}
